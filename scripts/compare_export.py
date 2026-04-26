"""
Compare an original JPEG against the cropped output to spot regressions in the
export pipeline. Pure-Python — no exiftool, no PIL — uses struct + raw JPEG
marker walking, in the same style as verify_pipeline.py.

Usage:
    py scripts/compare_export.py original.jpg cropped.jpg

Exit codes:
    0  No suspicious differences detected
    1  Suspicious differences (potential regression)
    2  Bad input (missing file, not a JPEG, etc.)

Compared categories:
    - File size sanity (cropped < original, but not implausibly small)
    - Bytes per megapixel (quality/density proxy)
    - Image dimensions (cropped strictly <= original on each axis)
    - JPEG quality estimate (from luma DQT[0][0] via IJG formula)
    - Chroma subsampling (4:4:4 / 4:2:2 / 4:2:0 from SOF0 sampling factors)
    - EXIF orientation (cropped should be 1 or 0; 2-8 means un-baked rotation)
    - EXIF tag presence (Make/Model/Lens/DateTime/MakerNote/Software)
    - EXIF GPS block preservation
    - EXIF IFD1 thumbnail (present + valid SOI/EOI in cropped)
    - ICC profile (presence, first-N-bytes match, data color space, PCS, version)
    - XMP hdrgm namespace + Version attribute (Ultra HDR spec compliance)
    - MPF (Multi-Picture Format) secondary-image count
    - Secondary JPEG (gain map) validation: SOI/EOI, dimensions vs primary
    - Samsung SEFT trailer (last 4 bytes "SEFT") + size
    - APP segment marker summary
"""

import struct
import sys


# ── Low-level helpers ────────────────────────────────────────────────────────

def r16be(d, o):
    return struct.unpack_from('>H', d, o)[0]


def r32be(d, o):
    return struct.unpack_from('>I', d, o)[0]


def r16(d, o, le):
    return struct.unpack_from('<H' if le else '>H', d, o)[0]


def r32(d, o, le):
    return struct.unpack_from('<I' if le else '>I', d, o)[0]


# ── JPEG segment walker ──────────────────────────────────────────────────────

def find_seft_end(data):
    """If data ends with the SEFT trailer, return the offset of the byte AFTER
    the last FF D9 before the SEFT footer. For files with a gain map between
    the primary and SEFT, this is the SECONDARY JPEG's EOI, not the primary's —
    which is what we want for APP-segment walking (stays within the outer
    boundary) but NOT what we want for gain-map discovery (use
    find_primary_eoi for that)."""
    if data[-4:] != b'SEFT':
        return len(data)
    for i in range(len(data) - 5, 1, -1):
        if data[i] == 0xFF and data[i + 1] == 0xD9:
            return i + 2
    return len(data)


def find_primary_eoi(data):
    """Forward-walk the JPEG structure to find the primary's EOI (first FF D9
    reached after parsing through SOS + entropy data). Returns the offset of
    the byte immediately after the FF D9, or None if no EOI reached.
    Entropy data uses FF 00 byte-stuffing and may contain RST markers
    (FF D0-D7) between restart intervals; any other FF xx ends the scan."""
    off = 2
    n = len(data)
    while off < n - 1:
        if data[off] != 0xFF:
            off += 1
            continue
        m = data[off + 1]
        if m == 0xD9:  # EOI of primary
            return off + 2
        if m == 0xDA:  # SOS — entropy data follows
            if off + 4 > n:
                return None
            seg_len = r16be(data, off + 2)
            if seg_len < 2:
                return None
            off += 2 + seg_len
            while off < n - 1:
                if data[off] == 0xFF:
                    nxt = data[off + 1]
                    if nxt == 0x00 or (0xD0 <= nxt <= 0xD7):
                        off += 2  # stuffed FF or restart marker — keep scanning
                        continue
                    break  # real marker — exit entropy data
                off += 1
            continue
        if m == 0x00 or m == 0x01 or 0xD0 <= m <= 0xD7 or m == 0xFF:
            off += 2
            continue
        if off + 4 > n:
            return None
        seg_len = r16be(data, off + 2)
        if seg_len < 2:
            return None
        off += 2 + seg_len
    return None


def walk_segments(data, end):
    """Yield (marker, payload_start, payload_end) for each APP/COM segment in
    the primary JPEG up to `end` (or its own EOI / SOS)."""
    if data[0] != 0xFF or data[1] != 0xD8:
        return
    off = 2
    while off < end - 1:
        if data[off] != 0xFF:
            off += 1
            continue
        marker = data[off + 1]
        if marker == 0xD9 or marker == 0xDA:  # EOI / SOS
            return
        if marker == 0x00 or marker == 0x01 or 0xD0 <= marker <= 0xD7 or marker == 0xFF:
            off += 2
            continue
        if off + 4 > end:
            return
        seg_len = r16be(data, off + 2)
        if seg_len < 2 or off + 2 + seg_len > end:
            return
        yield (marker, off + 4, off + 2 + seg_len)
        off += 2 + seg_len


def scan_apps(data, end):
    """Index APPn / COM payloads by marker. Returns dict marker -> [payload_bytes]."""
    out = {}
    for marker, ps, pe in walk_segments(data, end):
        if 0xE0 <= marker <= 0xEF or marker == 0xFE:
            out.setdefault(marker, []).append(data[ps:pe])
    return out


def find_marker_segment(data, start, end, target_markers):
    """Scan from `start` to `end` for any marker in `target_markers`. Returns
    (marker, segment_start_offset, seg_len) or (None, None, None). Segment
    start offset points to the FF byte; seg_len is the segment's length
    field (excluding the 2-byte marker, including the 2-byte length prefix)."""
    off = start
    while off < end - 3:
        if data[off] != 0xFF:
            off += 1
            continue
        m = data[off + 1]
        if m in target_markers:
            seg_len = r16be(data, off + 2) if off + 4 <= end else 0
            return (m, off, seg_len)
        if m == 0xD9 or m == 0xDA:
            return (None, None, None)
        if m == 0x00 or m == 0x01 or 0xD0 <= m <= 0xD7 or m == 0xFF:
            off += 2
            continue
        if off + 4 > end:
            return (None, None, None)
        seg_len = r16be(data, off + 2)
        if seg_len < 2:
            return (None, None, None)
        off += 2 + seg_len
    return (None, None, None)


# ── EXIF mini-parser ─────────────────────────────────────────────────────────

EXIF_HEADER = b'Exif\x00\x00'

EXIF_TAG_NAMES = {
    0x010F: 'Make',
    0x0110: 'Model',
    0x0112: 'Orientation',
    0x011A: 'XResolution',
    0x0131: 'Software',
    0x0132: 'DateTime',
    0x013B: 'Artist',
    0x0201: 'JPEGInterchangeFormat',
    0x0202: 'JPEGInterchangeFormatLength',
    0x8298: 'Copyright',
    0x9000: 'ExifVersion',
    0x9003: 'DateTimeOriginal',
    0x9004: 'DateTimeDigitized',
    0x920A: 'FocalLength',
    0x927C: 'MakerNote',
    0xA433: 'LensMake',
    0xA434: 'LensModel',
    0x8769: '_ExifIFD',
    0x8825: '_GPSIFD',
}


def _typ_size(t):
    return {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 9: 4, 10: 8}.get(t, 0)


def _read_ifd(tiff, off, le, out):
    """Return (entries_dict, next_ifd_offset). Only fills entries for tags in
    EXIF_TAG_NAMES (into `out`); returns (out, next_ifd_off) where next_ifd_off
    is the raw pointer stored after the IFD's entry list."""
    if off + 2 > len(tiff):
        return out, 0
    n = r16(tiff, off, le)
    for i in range(n):
        e = off + 2 + i * 12
        if e + 12 > len(tiff):
            break
        tag = r16(tiff, e, le)
        typ = r16(tiff, e + 2, le)
        cnt = r32(tiff, e + 4, le)
        val_or_off = r32(tiff, e + 8, le)
        name = EXIF_TAG_NAMES.get(tag)
        if not name:
            continue
        if typ == 3 and cnt == 1:
            out[name] = val_or_off & 0xFFFF
        elif typ == 4 and cnt == 1:
            out[name] = val_or_off
        else:
            sz = cnt * _typ_size(typ)
            data_off = val_or_off if sz > 4 else (e + 8)
            if 0 <= data_off and data_off + sz <= len(tiff):
                out[name] = tiff[data_off:data_off + sz]
            else:
                out[name] = b'<present>'
    next_ifd_off = 0
    tail = off + 2 + n * 12
    if tail + 4 <= len(tiff):
        next_ifd_off = r32(tiff, tail, le)
    return out, next_ifd_off


def parse_exif(payload):
    """Return (entries_dict, ifd1_dict_or_None, tiff_bytes, little_endian_bool).
    entries_dict aggregates IFD0 + ExifIFD + GPSIFD (for tag-name overlap fine);
    ifd1 is a separate dict (thumbnail IFD). Returns (None, None, None, None)
    when the payload isn't EXIF."""
    if not payload.startswith(EXIF_HEADER):
        return None, None, None, None
    tiff = payload[len(EXIF_HEADER):]
    if len(tiff) < 8:
        return None, None, None, None
    le = tiff[:2] == b'II'
    if not le and tiff[:2] != b'MM':
        return None, None, None, None
    if r16(tiff, 2, le) != 42:
        return None, None, None, None
    entries = {}
    ifd0_off = r32(tiff, 4, le)
    _, next_ifd_off = _read_ifd(tiff, ifd0_off, le, entries)
    if '_ExifIFD' in entries:
        try:
            _read_ifd(tiff, int(entries['_ExifIFD']), le, entries)
        except Exception:
            pass
    if '_GPSIFD' in entries:
        try:
            _read_ifd(tiff, int(entries['_GPSIFD']), le, entries)
        except Exception:
            pass
    ifd1 = None
    if next_ifd_off > 0:
        ifd1 = {}
        try:
            _read_ifd(tiff, next_ifd_off, le, ifd1)
        except Exception:
            ifd1 = None
    return entries, ifd1, tiff, le


def get_dimensions(data, end):
    """Find image width/height from the first SOFn marker."""
    off = 2
    while off < end - 9:
        if data[off] != 0xFF:
            off += 1
            continue
        m = data[off + 1]
        if 0xC0 <= m <= 0xCF and m not in (0xC4, 0xC8, 0xCC):
            seg_len = r16be(data, off + 2)
            if seg_len < 8:
                return None
            h = r16be(data, off + 5)
            w = r16be(data, off + 7)
            return (w, h)
        if 0xD0 <= m <= 0xD9 or m == 0xDA:
            return None
        if m == 0x00 or m == 0xFF:
            off += 2
            continue
        if off + 4 > end:
            return None
        seg_len = r16be(data, off + 2)
        if seg_len < 2:
            return None
        off += 2 + seg_len
    return None


def chroma_subsampling(data, end):
    """Parse SOF0..15 component sampling factors. YCbCr Y H×V sampling tells
    us 4:4:4 (1×1) / 4:2:2 (2×1) / 4:2:0 (2×2) / 4:1:1 (4×1). Returns the
    shorthand string or None when we can't determine (non-YCbCr, monochrome,
    etc.)."""
    off = 2
    while off < end - 9:
        if data[off] != 0xFF:
            off += 1
            continue
        m = data[off + 1]
        if 0xC0 <= m <= 0xCF and m not in (0xC4, 0xC8, 0xCC):
            seg_len = r16be(data, off + 2)
            if seg_len < 8:
                return None
            nf = data[off + 9]  # number of components
            if nf != 3 or off + 10 + nf * 3 > end:
                return None
            # Component 1 (Y): H in high nibble, V in low nibble of the
            # sampling-factors byte.
            samp_y = data[off + 11]
            h_y, v_y = samp_y >> 4, samp_y & 0x0F
            return {
                (1, 1): '4:4:4',
                (2, 1): '4:2:2',
                (2, 2): '4:2:0',
                (4, 1): '4:1:1',
            }.get((h_y, v_y), f'{h_y}:{v_y}')
        if 0xD0 <= m <= 0xD9 or m == 0xDA:
            return None
        if m == 0x00 or m == 0xFF:
            off += 2
            continue
        if off + 4 > end:
            return None
        seg_len = r16be(data, off + 2)
        if seg_len < 2:
            return None
        off += 2 + seg_len
    return None


# ── JPEG quality estimate (IJG formula inverse) ─────────────────────────────

# Standard IJG reference luminance quantization table (ITU-T T.81 Annex K.1).
IJG_REF_LUMA_DC = 16


def jpeg_quality_estimate(data, end):
    """Approximate the JPEG quality setting from the luminance DQT's first
    coefficient, using the IJG formula's inverse. Returns an integer 1-100,
    or None if no DQT found. Works on the first DQT encountered; subsequent
    tables may differ in the rare multi-table case."""
    off = 2
    while off < end - 4:
        if data[off] != 0xFF:
            off += 1
            continue
        m = data[off + 1]
        if m == 0xDB:  # DQT
            seg_len = r16be(data, off + 2)
            # Precision/table-id byte at off+4. Luma table has id 0; precision
            # high nibble: 0 = 8-bit, 1 = 16-bit. We want the 8-bit luma table.
            pt = data[off + 4]
            if (pt >> 4) == 0 and (pt & 0x0F) == 0 and off + 5 + 64 <= end:
                dc = data[off + 5]
                # IJG: scaled = max(1, min(255, (ref * scale + 50) // 100))
                # Inverse with ref=16: scale = (dc * 100 - 50) / 16
                # Then: scale = 200 - 2Q for Q>=50, scale = 5000/Q for Q<50
                if dc <= 0:
                    return 100
                scale = (dc * 100 - 50) / IJG_REF_LUMA_DC
                if scale <= 100:
                    q = round((200 - scale) / 2)
                else:
                    q = round(5000 / scale)
                return max(1, min(100, q))
            off += 2 + seg_len
            continue
        if m == 0xD9 or m == 0xDA:
            return None
        if m == 0x00 or m == 0xFF:
            off += 2
            continue
        if off + 4 > end:
            return None
        seg_len = r16be(data, off + 2)
        if seg_len < 2:
            return None
        off += 2 + seg_len
    return None


# ── Feature detectors ────────────────────────────────────────────────────────

def has_xmp_hdrgm(apps):
    """XMP packet starts with 'http://ns.adobe.com/xap/1.0/\\x00' in APP1.
    Look for the hdrgm namespace inside it."""
    for payload in apps.get(0xE1, []):
        if payload.startswith(b'http://ns.adobe.com/xap/1.0/\x00'):
            return b'hdrgm' in payload
    return False


def xmp_hdrgm_version(apps):
    """Extract the hdrgm:Version attribute value from XMP. Ultra HDR spec
    requires Version="1.0". Returns the version string or None."""
    for payload in apps.get(0xE1, []):
        if not payload.startswith(b'http://ns.adobe.com/xap/1.0/\x00'):
            continue
        # Look for `hdrgm:Version="X.Y"` or `hdrgm:Version='X.Y'` anywhere.
        for sep in (b'"', b"'"):
            key = b'hdrgm:Version=' + sep
            idx = payload.find(key)
            if idx < 0:
                continue
            start = idx + len(key)
            end = payload.find(sep, start)
            if 0 < end - start < 16:
                return payload[start:end].decode('ascii', errors='replace')
    return None


def icc_profile_bytes(apps):
    """ICC_PROFILE\\0 in APP2; segments are numbered (idx, total) per spec.
    Return concatenated payload (sans header) or None."""
    parts = []
    for payload in apps.get(0xE2, []):
        if payload.startswith(b'ICC_PROFILE\x00'):
            parts.append(payload[14:])
    return b''.join(parts) if parts else None


def parse_icc_header(icc):
    """Parse the 128-byte ICC header. Returns a dict with size, version,
    data_color_space, pcs, description (best-effort from 'desc' tag).
    Returns None when the payload is too short."""
    if not icc or len(icc) < 128:
        return None
    major = icc[8]
    minor_bcd = icc[9]
    version = f'{major}.{minor_bcd >> 4}'
    data_cs = icc[16:20].decode('ascii', errors='replace').rstrip()
    pcs = icc[20:24].decode('ascii', errors='replace').rstrip()
    desc = _icc_description(icc)
    return {
        'size': len(icc),
        'version': version,
        'data_cs': data_cs,
        'pcs': pcs,
        'description': desc,
    }


def _icc_description(icc):
    """Walk the ICC tag table for 'desc' and decode its string. ICCv2 uses the
    'desc' type (length-prefixed ASCII); ICCv4 uses 'mluc' (multi-localized
    UTF-16BE). Best-effort: returns a short ASCII description or None."""
    if len(icc) < 132:
        return None
    try:
        n_tags = struct.unpack_from('>I', icc, 128)[0]
    except struct.error:
        return None
    if n_tags > 128 or 132 + n_tags * 12 > len(icc):
        return None
    for i in range(n_tags):
        e = 132 + i * 12
        sig = icc[e:e + 4]
        off = struct.unpack_from('>I', icc, e + 4)[0]
        size = struct.unpack_from('>I', icc, e + 8)[0]
        if sig != b'desc' or off + size > len(icc):
            continue
        body = icc[off:off + size]
        if body[:4] == b'desc':  # ICCv2 desc
            # body[8:12] = ASCII count, body[12:] = ASCII string
            count = struct.unpack_from('>I', body, 8)[0]
            if 12 + count <= len(body):
                return body[12:12 + count].rstrip(b'\x00').decode(
                    'ascii', errors='replace')
        elif body[:4] == b'mluc':  # ICCv4 mluc
            n = struct.unpack_from('>I', body, 8)[0]
            rec_sz = struct.unpack_from('>I', body, 12)[0]
            if n < 1 or rec_sz < 12 or 16 + rec_sz > len(body):
                continue
            str_len = struct.unpack_from('>I', body, 16 + 8)[0]
            str_off = struct.unpack_from('>I', body, 16 + 12)[0]
            if 0 < str_len < 256 and str_off + str_len <= len(body):
                return body[str_off:str_off + str_len].decode(
                    'utf-16-be', errors='replace').rstrip('\x00')
    return None


def mpf_secondary_count(apps):
    """MPF APP2: 'MPF\\0' header, then a TIFF with NumberOfImages entry."""
    for payload in apps.get(0xE2, []):
        if not payload.startswith(b'MPF\x00'):
            continue
        tiff = payload[4:]
        if len(tiff) < 8:
            continue
        le = tiff[:2] == b'II'
        if not le and tiff[:2] != b'MM':
            continue
        ifd_off = r32(tiff, 4, le)
        if ifd_off + 2 > len(tiff):
            continue
        n = r16(tiff, ifd_off, le)
        for i in range(n):
            e = ifd_off + 2 + i * 12
            if e + 12 > len(tiff):
                break
            if r16(tiff, e, le) == 0xB001:  # NumberOfImages
                return r32(tiff, e + 8, le)
    return 0


def find_secondary_jpeg(data, _unused_end):
    """Scan from just after the primary's EOI for the start of a secondary
    JPEG (an FFD8 SOI marker). Returns (soi_offset, eoi_offset_inclusive,
    width, height) or None. Used to validate the gain map embedded via MPF.
    The caller's `primary_end` argument is ignored — it historically pointed
    past the SECONDARY EOI on Ultra HDR files, which is past where the gain
    map lives. We compute the right boundary (primary EOI) here."""
    primary_eoi = find_primary_eoi(data)
    if primary_eoi is None:
        return None
    off = primary_eoi
    limit = len(data) - 4
    # SEFT trailer (if any) sits between primary EOI and trailer footer —
    # the gain map, when present, is BEFORE the SEFT bytes but after primary.
    if data[-4:] == b'SEFT':
        limit = len(data) - 4
    while off < limit:
        if data[off] == 0xFF and data[off + 1] == 0xD8:
            # Found SOI candidate. Walk to EOI.
            sub_start = off
            sub = data[off:]
            # Find EOI in the secondary JPEG.
            end = sub_start + 2
            while end < limit - 1:
                if data[end] != 0xFF:
                    end += 1
                    continue
                m = data[end + 1]
                if m == 0xD9:  # EOI
                    dim = get_dimensions(sub, len(sub))
                    if dim:
                        return (sub_start, end + 1, dim[0], dim[1])
                    return (sub_start, end + 1, None, None)
                if m == 0xDA:  # SOS: walk entropy data to next FFxx
                    seg_len = r16be(data, end + 2)
                    end += 2 + seg_len
                    # Skip entropy data — look for next FFxx with xx != 0 and
                    # not in RSTn range.
                    while end < limit - 1:
                        if data[end] == 0xFF and data[end + 1] not in (
                                0x00, 0xFF) and not (0xD0 <= data[end + 1] <= 0xD7):
                            break
                        end += 1
                    continue
                if m == 0x00 or m == 0x01 or 0xD0 <= m <= 0xD7 or m == 0xFF:
                    end += 2
                    continue
                if end + 4 > limit:
                    break
                seg_len = r16be(data, end + 2)
                if seg_len < 2:
                    break
                end += 2 + seg_len
            return None
        off += 1
    return None


def ifd1_thumbnail(exif_payload):
    """Extract the IFD1 thumbnail (JPEG inline) from an EXIF APP1 payload.
    Returns (thumb_bytes, valid_jpeg_bool) or (None, False). JPEGInterchange-
    Format + JPEGInterchangeFormatLength offsets are relative to the TIFF
    header (start of `tiff` bytes, i.e. after the 'Exif\\0\\0' prefix)."""
    entries, ifd1, tiff, le = parse_exif(exif_payload)
    if not ifd1 or tiff is None:
        return None, False
    off_val = ifd1.get('JPEGInterchangeFormat')
    len_val = ifd1.get('JPEGInterchangeFormatLength')
    if off_val is None or len_val is None:
        return None, False
    try:
        tb_off = int(off_val)
        tb_len = int(len_val)
    except (TypeError, ValueError):
        return None, False
    if tb_off <= 0 or tb_len <= 0 or tb_off + tb_len > len(tiff):
        return None, False
    thumb = tiff[tb_off:tb_off + tb_len]
    valid = (len(thumb) >= 4 and thumb[:2] == b'\xff\xd8'
        and thumb[-2:] == b'\xff\xd9')
    return thumb, valid


def has_seft(data):
    return data[-4:] == b'SEFT'


def seft_size(data):
    if not has_seft(data):
        return 0
    return len(data) - find_seft_end(data)


def has_gps(entries):
    return entries is not None and '_GPSIFD' in entries


def _peek_orientation(apps):
    """Pull the EXIF Orientation tag without invoking the full parse_exif
    machinery. Returns 1 (default) when absent or unreadable."""
    for payload in apps.get(0xE1, []):
        entries, _, _, _ = parse_exif(payload)
        if entries and 'Orientation' in entries:
            try:
                return int(entries['Orientation'])
            except (TypeError, ValueError):
                pass
    return 1


def _display_dims(stored, orientation):
    """Return (W, H) as the user sees them, swapping for orientations that
    rotate 90°/270° (5/6/7/8). Returns input unchanged for missing dims or
    orientations 1/2/3/4 (which only flip/rotate-180, preserving aspect)."""
    if not stored:
        return stored
    w, h = stored
    if orientation in (5, 6, 7, 8):
        return (h, w)
    return (w, h)


# ── Comparison ───────────────────────────────────────────────────────────────

def load(path):
    with open(path, 'rb') as f:
        data = f.read()
    if data[:2] != b'\xff\xd8':
        sys.exit(f'Not a JPEG: {path}')
    return data


def fmt(v):
    if isinstance(v, (bytes, bytearray)):
        if len(v) > 32:
            return f'<{len(v)} bytes>'
        try:
            return v.rstrip(b'\x00').decode('ascii')
        except UnicodeDecodeError:
            return v.hex()
    return str(v)


def diff_row(label, a, b, ok):
    mark = 'OK ' if ok else '!! '
    a_s = fmt(a) if a is not None else '<absent>'
    b_s = fmt(b) if b is not None else '<absent>'
    print(f'  {mark}{label:30s}  {a_s:30s} -> {b_s}')


def main(argv):
    if len(argv) != 3:
        print(__doc__)
        return 2
    orig_path, crop_path = argv[1], argv[2]
    orig = load(orig_path)
    crop = load(crop_path)

    print(f'Original: {orig_path}  ({len(orig):,} bytes)')
    print(f'Cropped:  {crop_path}  ({len(crop):,} bytes)')
    print()

    suspicious = 0
    o_end = find_seft_end(orig)
    c_end = find_seft_end(crop)
    odim_raw = get_dimensions(orig, o_end)
    cdim_raw = get_dimensions(crop, c_end)
    o_apps = scan_apps(orig, o_end)
    c_apps = scan_apps(crop, c_end)

    # Account for EXIF orientation flips. When the original carries Orientation
    # 5/6/7/8, viewers see it rotated 90°/270° — CropCenter bakes that rotation
    # into the cropped pixels and emits Orientation=1. Comparing raw stored
    # dimensions then false-positives the "cropped <= original" check. Use
    # display-oriented dimensions for size/area comparisons so we measure what
    # the user actually saw before vs. after the crop.
    o_orient = _peek_orientation(o_apps)
    c_orient = _peek_orientation(c_apps)
    odim = _display_dims(odim_raw, o_orient)
    cdim = _display_dims(cdim_raw, c_orient)

    # ── File size + bytes-per-megapixel ──────────────────────────────────
    print('File size')
    ratio = len(crop) / len(orig)
    # Compute quality jump up-front so the size band can widen when the export
    # genuinely encodes at higher quality than the source (CropExporter hard-
    # codes q=100; a source shot at q=85 will produce a noticeably larger file
    # for the same pixel area without that being a regression).
    o_q_pre = jpeg_quality_estimate(orig, o_end)
    c_q_pre = jpeg_quality_estimate(crop, c_end)
    quality_inflate = 1.0
    if o_q_pre and c_q_pre and c_q_pre > o_q_pre:
        # Each ~5-quality-point bump roughly doubles bytes-per-pixel near the
        # high end (95→100 is the steepest). Cap the inflation factor at 2.5×
        # so a wildly bloated export still flags.
        quality_inflate = min(2.5, 1.0 + (c_q_pre - o_q_pre) * 0.15)
    if odim and cdim:
        pix_ratio = (cdim[0] * cdim[1]) / (odim[0] * odim[1])
        upper = pix_ratio * 1.8 * quality_inflate
        lower = pix_ratio * 0.3
        in_band = lower <= ratio <= max(upper, 0.05)
        diff_row('size ratio (crop/orig)', f'{ratio:.3f}',
            f'expected {lower:.3f}..{upper:.3f}', in_band)
        if not in_band:
            suspicious += 1
        # Bytes per megapixel — quality/density proxy. Massive drop indicates
        # quality regression even when metadata looks fine.
        o_bpp = len(orig) / (odim[0] * odim[1] / 1_000_000)
        c_bpp = len(crop) / (cdim[0] * cdim[1] / 1_000_000)
        bpp_ok = c_bpp >= o_bpp * 0.6
        diff_row('bytes / megapixel', f'{o_bpp:,.0f}', f'{c_bpp:,.0f}', bpp_ok)
        if not bpp_ok:
            suspicious += 1
    print()

    # ── Dimensions ──────────────────────────────────────────────────────
    print('Dimensions')
    diff_row('image size', f'{odim[0]}x{odim[1]}' if odim else None,
        f'{cdim[0]}x{cdim[1]}' if cdim else None, bool(cdim))
    if odim and cdim:
        ok = cdim[0] <= odim[0] and cdim[1] <= odim[1]
        diff_row('cropped <= original', '', '', ok)
        if not ok:
            suspicious += 1
    print()

    # ── JPEG encoding quality ────────────────────────────────────────────
    print('JPEG encoding')
    o_q = jpeg_quality_estimate(orig, o_end)
    c_q = jpeg_quality_estimate(crop, c_end)
    # Cropped should be >= 90 (CropExporter hardcodes quality=100); flag if
    # noticeably below the original.
    q_ok = c_q is not None and c_q >= 90 and (o_q is None or c_q >= o_q - 5)
    diff_row('quality estimate (luma DQT)', o_q, c_q, q_ok)
    if not q_ok:
        suspicious += 1
    o_sub = chroma_subsampling(orig, o_end)
    c_sub = chroma_subsampling(crop, c_end)
    # Prefer cropped not to downgrade subsampling (e.g. 4:4:4 → 4:2:0).
    sub_ok = (o_sub == c_sub) or (c_sub in ('4:4:4', '4:2:2') and o_sub == '4:2:0')
    diff_row('chroma subsampling', o_sub, c_sub, sub_ok)
    if not sub_ok:
        suspicious += 1
    print()

    # ── EXIF ────────────────────────────────────────────────────────────
    print('EXIF')
    o_parsed = next((parse_exif(p) for p in o_apps.get(0xE1, [])
        if parse_exif(p)[0] is not None), (None, None, None, None))
    c_parsed = next((parse_exif(p) for p in c_apps.get(0xE1, [])
        if parse_exif(p)[0] is not None), (None, None, None, None))
    o_exif = o_parsed[0] or {}
    c_exif = c_parsed[0] or {}

    interesting = ['Make', 'Model', 'LensModel', 'DateTimeOriginal',
        'FocalLength', 'MakerNote', 'Software', 'Orientation']
    for tag in interesting:
        ov = o_exif.get(tag)
        cv = c_exif.get(tag)
        if tag == 'Orientation':
            # Cropped should be 1 (rotation baked in). Values 2-8 mean un-
            # baked rotation — a real regression. Value 0 is out-of-spec
            # (viewers default to 1), flag only strict 2-8.
            ok = cv is None or cv == 1 or not (2 <= cv <= 8)
            diff_row(tag, ov, cv, ok)
            if not ok:
                suspicious += 1
        elif tag == 'Software':
            # Informational: just show whatever's there on both sides.
            diff_row(tag, ov, cv, True)
        else:
            if ov is None:
                continue
            present = cv is not None
            diff_row(tag, ov, cv, present)
            if not present:
                suspicious += 1

    # GPS presence
    o_gps = has_gps(o_exif)
    c_gps = has_gps(c_exif)
    gps_ok = (not o_gps) or c_gps
    diff_row('GPS block', o_gps, c_gps, gps_ok)
    if not gps_ok:
        suspicious += 1

    # IFD1 thumbnail
    o_thumb = None
    c_thumb = None
    c_thumb_valid = False
    for p in o_apps.get(0xE1, []):
        t, _ = ifd1_thumbnail(p)
        if t:
            o_thumb = t
            break
    for p in c_apps.get(0xE1, []):
        t, v = ifd1_thumbnail(p)
        if t:
            c_thumb = t
            c_thumb_valid = v
            break
    thumb_ok = c_thumb is not None and c_thumb_valid
    diff_row('IFD1 thumbnail (SOI..EOI)',
        f'{len(o_thumb)} B' if o_thumb else None,
        f'{len(c_thumb)} B valid={c_thumb_valid}' if c_thumb else None,
        thumb_ok)
    if not thumb_ok:
        suspicious += 1
    print()

    # ── ICC profile ─────────────────────────────────────────────────────
    print('ICC profile')
    o_icc = icc_profile_bytes(o_apps)
    c_icc = icc_profile_bytes(c_apps)
    if o_icc:
        present = c_icc is not None
        head_match = present and o_icc[:128] == c_icc[:128]
        diff_row('present',
            f'{len(o_icc)} bytes',
            f'{len(c_icc)} bytes' if c_icc else None,
            present)
        if present:
            diff_row('first 128 bytes match', '', '', head_match)
            if not head_match:
                suspicious += 1
            o_hdr = parse_icc_header(o_icc) or {}
            c_hdr = parse_icc_header(c_icc) or {}
            diff_row('version', o_hdr.get('version'), c_hdr.get('version'),
                o_hdr.get('version') == c_hdr.get('version'))
            diff_row('data color space', o_hdr.get('data_cs'),
                c_hdr.get('data_cs'),
                o_hdr.get('data_cs') == c_hdr.get('data_cs'))
            diff_row('PCS', o_hdr.get('pcs'), c_hdr.get('pcs'),
                o_hdr.get('pcs') == c_hdr.get('pcs'))
            if o_hdr.get('description') or c_hdr.get('description'):
                diff_row('description', o_hdr.get('description'),
                    c_hdr.get('description'), True)
        else:
            suspicious += 1
    else:
        diff_row('present', '<absent>',
            f'{len(c_icc)} bytes' if c_icc else '<absent>', True)
    print()

    # ── XMP / hdrgm ─────────────────────────────────────────────────────
    print('XMP / HDR signal')
    o_hdr = has_xmp_hdrgm(o_apps)
    c_hdr = has_xmp_hdrgm(c_apps)
    diff_row('xmp:hdrgm namespace', o_hdr, c_hdr, o_hdr == c_hdr or c_hdr)
    if o_hdr and not c_hdr:
        suspicious += 1
    o_ver = xmp_hdrgm_version(o_apps)
    c_ver = xmp_hdrgm_version(c_apps)
    # Ultra HDR spec requires hdrgm:Version="1.0"; flag if original had it
    # but cropped doesn't.
    ver_ok = (o_ver is None) or (c_ver is not None)
    diff_row('hdrgm:Version attr', o_ver, c_ver, ver_ok)
    if not ver_ok:
        suspicious += 1
    print()

    # ── MPF / Ultra HDR secondary ───────────────────────────────────────
    print('MPF / secondary JPEG (gain map)')
    o_mpf = mpf_secondary_count(o_apps)
    c_mpf = mpf_secondary_count(c_apps)
    mpf_ok = o_mpf <= c_mpf
    diff_row('image count (>=2 = HDR)', o_mpf, c_mpf, mpf_ok)
    if o_mpf >= 2 and c_mpf < 2:
        suspicious += 1
    # If original has a secondary, cropped should too. Inspect it for validity.
    o_sec = find_secondary_jpeg(orig, o_end) if o_mpf >= 2 else None
    c_sec = find_secondary_jpeg(crop, c_end) if c_mpf >= 2 else None
    if o_sec or c_sec:
        o_desc = (f'{o_sec[2]}x{o_sec[3]} @ off {o_sec[0]}'
            if o_sec and o_sec[2] else '<not parseable>' if o_sec else None)
        c_desc = (f'{c_sec[2]}x{c_sec[3]} @ off {c_sec[0]}'
            if c_sec and c_sec[2] else '<not parseable>' if c_sec else None)
        sec_ok = bool(c_sec and c_sec[2] and c_sec[3])
        diff_row('secondary JPEG', o_desc, c_desc, sec_ok)
        if not sec_ok and o_sec is not None:
            suspicious += 1
        # Gain map is typically half or quarter resolution of primary. The gain
        # map's stored orientation matches its primary's, so rotate its dims by
        # the same orientation as the primary for an apples-to-apples ratio
        # against the display-oriented primary width.
        o_gm_disp = (_display_dims((o_sec[2], o_sec[3]), o_orient)
            if o_sec and o_sec[2] else None)
        c_gm_disp = (_display_dims((c_sec[2], c_sec[3]), c_orient)
            if c_sec and c_sec[2] else None)
        o_gm_ratio = (f'{o_gm_disp[0] / odim[0]:.2f}x primary W'
            if o_gm_disp and odim else None)
        c_gm_ratio = (f'{c_gm_disp[0] / cdim[0]:.2f}x primary W'
            if c_gm_disp and cdim else None)
        ratio_ok = True
        if o_gm_disp and c_gm_disp and odim and cdim:
            o_r = o_gm_disp[0] / odim[0]
            c_r = c_gm_disp[0] / cdim[0]
            ratio_ok = abs(o_r - c_r) < 0.1
        diff_row('gain map / primary W', o_gm_ratio, c_gm_ratio, ratio_ok)
        if not ratio_ok:
            suspicious += 1
    print()

    # ── Samsung SEFT trailer ────────────────────────────────────────────
    print('Samsung SEFT trailer')
    seft_present_ok = has_seft(orig) == has_seft(crop) or has_seft(crop)
    diff_row('present', has_seft(orig), has_seft(crop), seft_present_ok)
    if has_seft(orig) and not has_seft(crop):
        suspicious += 1
    if has_seft(orig) or has_seft(crop):
        size_ok = abs(seft_size(orig) - seft_size(crop)) <= seft_size(orig) * 0.5
        diff_row('size', seft_size(orig), seft_size(crop), size_ok)
    print()

    # ── APP segment summary ─────────────────────────────────────────────
    print('APP segment markers (orig -> crop)')
    o_markers = sorted(o_apps.keys())
    c_markers = sorted(c_apps.keys())
    for m in sorted(set(o_markers) | set(c_markers)):
        oc = len(o_apps.get(m, []))
        cc = len(c_apps.get(m, []))
        ok = (oc == 0) or (cc > 0)
        label = (f'APP{m & 0x0F}' if 0xE0 <= m <= 0xEF
            else 'COM' if m == 0xFE else f'0x{m:02X}')
        diff_row(f'{label} (0x{m:02X})', oc, cc, ok)
    print()

    # ── Verdict ─────────────────────────────────────────────────────────
    if suspicious == 0:
        print('VERDICT: clean — no suspicious differences detected.')
        return 0
    print(f'VERDICT: {suspicious} suspicious difference(s). Inspect items '
        'flagged with !!.')
    return 1


if __name__ == '__main__':
    sys.exit(main(sys.argv))
