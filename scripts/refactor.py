#!/usr/bin/env python3
"""Consolidated source-refactoring CLI for CropCenter.

Replaces four separate scripts (reflow_code.py, reflow_comments.py, reflow_md.py,
strip_super_blanks.py) with a single entry point. Each transform lives as a
function inside this file; shared helpers (Java file walk, EOL-preserving
read/write, display-width) live at the top.

Usage:
    python scripts/refactor.py <name> [args...]

Transforms:
    code               join multi-line Java statements that fit under 120 cols
    comments           reflow Javadoc + // comment paragraphs to 120 cols
    md <file> [width]  reflow markdown paragraphs to width (default 120)
    strip-blanks       remove blank lines around braces, collapse double-blanks

Examples:
    python scripts/refactor.py code               # walks app/src/main+test
    python scripts/refactor.py code --check       # dry-run, print would-change
    python scripts/refactor.py code Foo.java Bar.java
    python scripts/refactor.py comments           # walks app/src tree
    python scripts/refactor.py md REQUIREMENTS.md 120
    python scripts/refactor.py strip-blanks       # walks app/src

Each subcommand keeps the prior script's exact behaviour. `code` and `comments`
accept `--check` for dry-run before the path arguments.
"""
import os
import re
import sys


MAX_COLS = 120
TAB_WIDTH = 8


# ─── shared helpers ──────────────────────────────────────────────────────────

def walk_java_files(roots):
	"""Yield (path) for every .java file under each root. Used when no
	explicit file list is provided."""
	for root in roots:
		for dp, _, fns in os.walk(root):
			for fn in fns:
				if fn.endswith('.java'):
					yield os.path.join(dp, fn)


def display_width(s):
	"""Compute display column count, counting tab as TAB_WIDTH (or to next
	multiple of TAB_WIDTH). Shared across reflow_code and reflow_comments —
	both need to decide whether a candidate join would fit under MAX_COLS."""
	cols = 0
	for c in s:
		if c == '\t':
			cols = (cols // TAB_WIDTH + 1) * TAB_WIDTH
		else:
			cols += 1
	return cols


def read_preserving_eol(path):
	"""Return (text, eol_marker_bytes). Detects CRLF vs LF in the raw bytes
	and decodes to a str with `\\n` as the line separator. The eol marker
	is round-tripped to write_preserving_eol so the file's line ending
	convention survives the rewrite."""
	with open(path, 'rb') as f:
		raw = f.read()
	if b'\r\n' in raw:
		return raw.decode('utf-8').replace('\r\n', '\n'), b'\r\n'
	return raw.decode('utf-8'), b'\n'


def write_preserving_eol(path, text, eol):
	"""Write `text` to `path` substituting back the original EOL marker.
	`text` is expected to use `\\n` internally; this routine restores the
	on-disk form before writing."""
	out_bytes = text.replace('\n', eol.decode('latin-1')).encode('utf-8')
	with open(path, 'wb') as f:
		f.write(out_bytes)


def resolve_file_args(args, default_roots):
	"""Return a flat list of .java paths. If args is empty, walk the default
	roots. If args contains directories, walk them. Otherwise treat each as
	a file path. Used by code/comments/strip-blanks which all accept either
	explicit files or a directory walk."""
	if not args:
		args = default_roots
	out = []
	for a in args:
		if os.path.isdir(a):
			out.extend(walk_java_files([a]))
		else:
			out.append(a)
	return out


# ─── transform: code ─────────────────────────────────────────────────────────

_TRAIL_OP = re.compile(r'(?:[+,(\[^]|(?<![|])\|(?![|])|(?<![&])&(?![&])|&&|\|\|)\s*$')
_LEAD_OP = re.compile(r'^\s*(?:[+^]|(?<![|])\|(?![|])|(?<![&])&(?![&])|&&|\|\|)\s')
_LEAD_CLOSER = re.compile(r'^\s*\)(?![\s]*\{)')


def _is_comment_line(line):
	s = line.lstrip()
	return s.startswith('//') or s.startswith('*') or s.startswith('/*')


def _in_string_literal_at_eol(line):
	"""Best-effort odd-quote count outside of `//` line comments. Joining is
	disallowed when the candidate line ends inside a string literal."""
	in_str = False
	i = 0
	while i < len(line):
		c = line[i]
		if not in_str and c == '/' and i + 1 < len(line) and line[i + 1] == '/':
			break
		if c == '"' and (i == 0 or line[i - 1] != '\\'):
			in_str = not in_str
		i += 1
	return in_str


def _can_join_code(line_a, line_b):
	"""True when line_a and line_b can be safely joined into a single line
	under MAX_COLS. Skips method chains (`.foo()`), ternary continuations
	(`? :`), Allman braces, and lines inside string literals. Joins on
	trailing-operator OR leading-operator/closer continuation forms."""
	sa = line_a.rstrip()
	sb = line_b
	if not sa.strip() or not sb.strip():
		return False
	if _is_comment_line(line_a) or _is_comment_line(line_b):
		return False
	if sa.endswith('{') or sb.lstrip().startswith('{'):
		return False
	if sb.lstrip().startswith('}'):
		return False
	if sb.lstrip().startswith('.'):
		return False
	if sb.lstrip().startswith('?') or sb.lstrip().startswith(':'):
		return False
	if sa.endswith('?') or sa.endswith(':'):
		return False
	if _in_string_literal_at_eol(sa):
		return False
	has_trail = bool(_TRAIL_OP.search(sa))
	has_lead = bool(_LEAD_OP.match(sb)) or bool(_LEAD_CLOSER.match(sb))
	if not has_trail and not has_lead:
		return False
	sep = '' if (sa.endswith(('(', '[')) or _LEAD_CLOSER.match(sb)) else ' '
	joined = sa + sep + sb.lstrip()
	if display_width(joined) > MAX_COLS:
		return False
	return True


def _join_code_pair(line_a, line_b):
	"""Combine two lines into one, preserving line_a's leading whitespace and
	dropping line_b's. Empty separator when sa opens with `(`/`[` or sb
	begins with `)`; single space otherwise."""
	sa = line_a.rstrip()
	sb = line_b.lstrip()
	sep = '' if (sa.endswith(('(', '[')) or _LEAD_CLOSER.match(line_b)) else ' '
	return sa + sep + sb


def _reflow_code_text(text):
	"""Iterate join passes until no more joins fire. Returns (text, passes)."""
	lines = text.split('\n')
	pass_num = 0
	while True:
		pass_num += 1
		out = []
		joined_count = 0
		i = 0
		n = len(lines)
		while i < n:
			if i + 1 < n and _can_join_code(lines[i], lines[i + 1]):
				out.append(_join_code_pair(lines[i], lines[i + 1]))
				i += 2
				joined_count += 1
			else:
				out.append(lines[i])
				i += 1
		if joined_count == 0:
			break
		lines = out
	return '\n'.join(lines), pass_num - 1


def refactor_code(args):
	"""Conservative code reflow — joins multi-line Java statements that fit
	under 120 cols. Safe to join on trailing `,` `+` `&&` `||` `(`, on
	leading-operator continuation, and on leading-closer continuation.
	Explicit skips: method chains, ternary, Allman braces, string literals.
	`--check` does a dry-run that prints would-change files."""
	dry_run = False
	if args and args[0] == '--check':
		dry_run = True
		args = args[1:]
	files = resolve_file_args(args, ['app/src/main/java', 'app/src/test/java'])
	total = changed = total_delta = 0
	for path in files:
		text, eol = read_preserving_eol(path)
		new_text, _ = _reflow_code_text(text)
		total += 1
		if new_text == text:
			continue
		delta = text.count('\n') - new_text.count('\n')
		changed += 1
		total_delta += delta
		tag = '[CHECK]' if dry_run else '[REFLOW]'
		print(f'  {tag} {path}: -{delta} lines')
		if not dry_run:
			write_preserving_eol(path, new_text, eol)
	verb = 'would change' if dry_run else 'changed'
	print(f'\n{changed}/{total} files {verb}, {total_delta} lines saved')
	return 0


# ─── transform: comments ─────────────────────────────────────────────────────

def _is_list_marker(line):
	s = line.lstrip()
	if not s:
		return False
	if s[:2] in ('- ', '* '):
		return True
	if s.startswith('-\t') or s.startswith('*\t'):
		return True
	if re.match(r'^\d+[.)]\s', s):
		return True
	if re.match(r'^\([a-zA-Z]\)\s', s):
		return True
	if s.startswith('→ '):
		return True
	return False


def _is_tag_line(line):
	return line.lstrip().startswith('@')


def _has_uneven_indent(paragraph):
	indents = []
	for line in paragraph:
		if not line.strip():
			continue
		indents.append(len(line) - len(line.lstrip(' \t')))
	return len(set(indents)) > 1


def _is_section_divider(line):
	s = line.strip()
	return '───' in s or '═══' in s


def _should_skip_paragraph(paragraph):
	"""True when the paragraph should be left alone (not reflowed). Trips on
	tags, list markers, intro-colon lines, section dividers, and paragraphs
	whose lines have visibly different indentation (code samples, tables)."""
	if len(paragraph) < 2:
		return True
	if _has_uneven_indent(paragraph):
		return True
	for i, line in enumerate(paragraph):
		if _is_tag_line(line) or _is_list_marker(line) or _is_section_divider(line):
			return True
		if line.rstrip().endswith(':'):
			return True
		if line.strip() and ' ' not in line.strip() and i < len(paragraph) - 1:
			return True
	return False


def _wrap_to_width(text, prefix, max_cols):
	prefix_cols = display_width(prefix)
	budget = max_cols - prefix_cols
	if budget < 20:
		return [prefix + text]
	words = text.split()
	if not words:
		return []
	lines = []
	current = [words[0]]
	current_len = len(words[0])
	for word in words[1:]:
		added = 1 + len(word)
		if current_len + added > budget:
			lines.append(prefix + ' '.join(current))
			current = [word]
			current_len = len(word)
		else:
			current.append(word)
			current_len += added
	if current:
		lines.append(prefix + ' '.join(current))
	return lines


def _join_paragraph_lines(lines):
	"""Join paragraph lines into one. Soft-wrap hyphenation (line ends with
	`-` and next line starts lowercase) joins with no space."""
	cleaned = [s.strip() for s in lines if s.strip()]
	if not cleaned:
		return ''
	out = cleaned[0]
	for piece in cleaned[1:]:
		if out.endswith('-') and not out.endswith('--') and piece and piece[0].islower():
			out = out + piece
		else:
			out = out + ' ' + piece
	return out


def _reflow_javadoc_block(text, indent_str):
	"""Reflow the inside of a `/** ... */` block. `text` is the raw content
	between (and not including) the `/**` and `*/` delimiters."""
	lines = text.split('\n')
	paragraph = []
	paragraph_raw_lines = []
	out = []

	def flush():
		if not paragraph:
			return
		if _should_skip_paragraph(paragraph):
			out.extend(paragraph_raw_lines)
		else:
			joined = _join_paragraph_lines(paragraph)
			out.extend(_wrap_to_width(joined, indent_str + ' * ', MAX_COLS))
		paragraph.clear()
		paragraph_raw_lines.clear()

	for raw_line in lines:
		m = re.match(r'^(\s*\*)(\s?)(.*)$', raw_line)
		if not m:
			flush()
			out.append(raw_line)
			continue
		prefix, _, content = m.group(1), m.group(2), m.group(3)
		if content.strip() == '':
			flush()
			out.append(prefix.rstrip())
			continue
		paragraph.append(content)
		paragraph_raw_lines.append(raw_line)
	flush()
	return '\n'.join(out)


def _reflow_inline_comment_run(lines, indent_str):
	"""Reflow a contiguous run of `//` lines at the same indent."""
	text_lines = []
	for line in lines:
		m = re.match(r'^\s*//(\s?)(.*)$', line)
		if not m:
			return lines
		text_lines.append(m.group(2))
	if _should_skip_paragraph(text_lines):
		return lines
	joined = _join_paragraph_lines(text_lines)
	return _wrap_to_width(joined, indent_str + '// ', MAX_COLS)


def _reflow_comments_text(text):
	"""Walk the file, find Javadoc + // runs, reflow each. Returns new text."""
	out = []
	i = 0
	lines = text.split('\n')
	n = len(lines)
	while i < n:
		line = lines[i]
		m_jdoc_open = re.match(r'^(\s*)/\*\*\s*$', line)
		if m_jdoc_open:
			indent = m_jdoc_open.group(1)
			j = i + 1
			while j < n and not re.match(r'^\s*\*/\s*$', lines[j]):
				j += 1
			if j < n:
				body = '\n'.join(lines[i + 1:j])
				reflowed = _reflow_javadoc_block(body, indent)
				out.append(line)
				if reflowed:
					out.extend(reflowed.split('\n'))
				out.append(lines[j])
				i = j + 1
				continue
		m_inline = re.match(r'^(\s*)//', line)
		if m_inline:
			indent = m_inline.group(1)
			run = [line]
			j = i + 1
			while j < n:
				m_next = re.match(r'^(\s*)//', lines[j])
				if not m_next or m_next.group(1) != indent:
					break
				run.append(lines[j])
				j += 1
			if len(run) >= 2:
				reflowed = _reflow_inline_comment_run(run, indent)
				out.extend(reflowed)
				i = j
				continue
		out.append(line)
		i += 1
	return '\n'.join(out)


def refactor_comments(args):
	"""Reflow Javadoc + `//` comment paragraphs to 120 cols. Skips tag lines
	(`@param` etc), list markers, intro-colon lines, section dividers, and
	paragraphs with uneven indentation. `--check` does a dry-run."""
	dry_run = False
	if args and args[0] == '--check':
		dry_run = True
		args = args[1:]
	files = resolve_file_args(args, ['app/src/main/java', 'app/src/test/java'])
	total = changed = 0
	for path in files:
		text, eol = read_preserving_eol(path)
		new_text = _reflow_comments_text(text)
		total += 1
		if new_text == text:
			continue
		changed += 1
		before, after = text.count('\n'), new_text.count('\n')
		tag = '[CHECK]' if dry_run else '[REFLOW]'
		print(f'  {tag} {path}: {before} -> {after} lines')
		if not dry_run:
			write_preserving_eol(path, new_text, eol)
	verb = 'would change' if dry_run else 'changed'
	print(f'\n{changed}/{total} files {verb}')
	return 0


# ─── transform: md ───────────────────────────────────────────────────────────

_HEADING_RE = re.compile(r'^#+\s')
_HRULE_RE = re.compile(r'^(\*{3,}|-{3,}|_{3,})\s*$')
_TABLE_RE = re.compile(r'^\s*\|')
_CODE_FENCE_RE = re.compile(r'^\s*```')
_LIST_ITEM_RE = re.compile(r'^(\s*)([-*+]|\d+\.)\s+(.*)$')
_DEFINITION_RE = re.compile(r'^\*\*[^*]+\*\*\s*:?\s*[^*]')


def _is_md_block_starter(line):
	s = line.lstrip()
	return (
		not s
		or _HEADING_RE.match(s)
		or _HRULE_RE.match(s)
		or _TABLE_RE.match(line)
		or _CODE_FENCE_RE.match(line)
		or _LIST_ITEM_RE.match(line)
		or _DEFINITION_RE.match(s)
		or line.startswith('>')
	)


def _join_md_paragraph(buf):
	if not buf:
		return ''
	out = buf[0]
	for line in buf[1:]:
		if out.endswith('-') and line and line[0].islower() and not out.endswith('--'):
			out = out + line
		else:
			out = out + ' ' + line
	return out


def _wrap_md_text(text, width, indent=''):
	"""Wrap a logical line to `width`, breaking at spaces. Words longer than
	the budget stay on their own line (don't break inside URLs / identifiers
	/ inline code)."""
	words = text.split()
	if not words:
		return ['']
	lines = []
	current = words[0]
	current_width = len(current)
	for w in words[1:]:
		if current_width + 1 + len(w) <= width:
			current += ' ' + w
			current_width += 1 + len(w)
		else:
			lines.append(current)
			current = indent + w
			current_width = len(current)
	lines.append(current)
	return lines


def _reflow_md_text(src, width):
	lines = src.split('\n')
	out = []
	i = 0
	in_code = False
	while i < len(lines):
		line = lines[i]
		stripped = line.strip()
		if _CODE_FENCE_RE.match(line):
			out.append(line)
			in_code = not in_code
			i += 1
			continue
		if in_code:
			out.append(line)
			i += 1
			continue
		if (not stripped
				or _HEADING_RE.match(stripped)
				or _HRULE_RE.match(stripped)
				or _TABLE_RE.match(line)
				or line.startswith('>')):
			out.append(line)
			i += 1
			continue
		m = _LIST_ITEM_RE.match(line)
		if m:
			base_indent = m.group(1)
			bullet = m.group(2)
			first_text = m.group(3)
			cont_indent = base_indent + ' ' * (len(bullet) + 1)
			buf = [first_text]
			j = i + 1
			while j < len(lines):
				nxt = lines[j]
				if not nxt.strip() or _is_md_block_starter(nxt):
					break
				buf.append(nxt.strip())
				j += 1
			joined = _join_md_paragraph(buf)
			first_prefix = base_indent + bullet + ' '
			wrapped = _wrap_md_text(joined, width - len(first_prefix), indent='')
			out.append(first_prefix + wrapped[0])
			for w in wrapped[1:]:
				out.append(cont_indent + w)
			i = j
			continue
		first_indent_m = re.match(r'^(\s*)(.*)$', line)
		para_indent = first_indent_m.group(1)
		buf = [first_indent_m.group(2).rstrip()]
		j = i + 1
		while j < len(lines):
			nxt = lines[j]
			if not nxt.strip() or _is_md_block_starter(nxt):
				break
			buf.append(nxt.strip())
			j += 1
		joined = _join_md_paragraph(buf)
		for w in _wrap_md_text(joined, width - len(para_indent)):
			out.append(para_indent + w)
		i = j
	return '\n'.join(out)


def refactor_md(args):
	"""Reflow markdown paragraphs to a target column width (default 120).
	Skips fenced code blocks, tables, headings, horizontal rules, and
	definition lines. Preserves the file's existing EOL convention (CRLF vs LF)."""
	if not args:
		print('usage: refactor.py md <file> [width]', file=sys.stderr)
		return 2
	path = args[0]
	width = int(args[1]) if len(args) > 1 else MAX_COLS
	text, eol = read_preserving_eol(path)
	new_text = _reflow_md_text(text, width)
	if new_text == text:
		print(f'  {path}: no changes')
		return 0
	write_preserving_eol(path, new_text, eol)
	over = [(idx + 1, len(line)) for idx, line in enumerate(new_text.split('\n')) if len(line) > width]
	if over:
		print(f'  {path}: rewrapped; {len(over)} line(s) still over {width} cols (likely tables):')
		for line_no, cols in over:
			print(f'    line {line_no}: {cols} cols')
	else:
		print(f'  {path}: rewrapped; all lines now <= {width} cols')
	return 0


# ─── transform: strip-blanks ─────────────────────────────────────────────────

def _strip_blanks_text(text):
	"""Three passes: remove blanks before `}` lines, remove blanks after `{`
	lines, collapse runs of 2+ blank lines to one. Returns (new_text, eol)."""
	if '\r\n' in text:
		nl = '\r\n'
	else:
		nl = '\n'
	lines = text.split(nl)
	trailing_nl = text.endswith(nl)
	if trailing_nl:
		lines = lines[:-1]
	# Pass 1: drop blanks before `}` lines.
	out = []
	for line in lines:
		stripped = line.strip()
		if stripped and stripped[0] == '}':
			while out and out[-1].strip() == '':
				out.pop()
		out.append(line)
	# Pass 2: drop blanks after lines ending in `{`.
	out2 = []
	skip_blanks = False
	for line in out:
		stripped = line.strip()
		if skip_blanks and stripped == '':
			continue
		skip_blanks = stripped.endswith('{') if stripped else False
		out2.append(line)
	# Pass 3: collapse double-blanks.
	out3 = []
	prev_blank = False
	for line in out2:
		is_blank = line.strip() == ''
		if is_blank and prev_blank:
			continue
		out3.append(line)
		prev_blank = is_blank
	new_text = nl.join(out3)
	if trailing_nl:
		new_text += nl
	return new_text, len(lines) - len(out3)


def refactor_strip_blanks(args):
	"""Strip three superfluous-blank patterns from Java sources: blanks
	before `}` lines, blanks after `{` lines, runs of 2+ blanks. Idempotent."""
	roots_or_files = args or ['app/src']
	files_scanned = files_changed = total_removed = 0
	for arg in roots_or_files:
		paths = walk_java_files([arg]) if os.path.isdir(arg) else [arg]
		for path in paths:
			files_scanned += 1
			with open(path, encoding='utf-8', newline='') as f:
				text = f.read()
			new_text, removed = _strip_blanks_text(text)
			if new_text == text:
				continue
			with open(path, 'w', encoding='utf-8', newline='') as f:
				f.write(new_text)
			files_changed += 1
			total_removed += removed
			print(f'  {removed:>3}  {path}')
	print(f'\n{files_scanned} files scanned, {files_changed} modified, '
		f'{total_removed} blank line(s) removed.')
	return 0


# ─── dispatcher ──────────────────────────────────────────────────────────────

TRANSFORMS = {
	'code': refactor_code,
	'comments': refactor_comments,
	'md': refactor_md,
	'strip-blanks': refactor_strip_blanks,
}


def main():
	args = sys.argv[1:]
	if not args or args[0] in ('-h', '--help'):
		print(__doc__)
		return 0
	name = args[0]
	if name not in TRANSFORMS:
		print(f'unknown transform: {name}', file=sys.stderr)
		print(f'available: {", ".join(TRANSFORMS.keys())}', file=sys.stderr)
		return 2
	return TRANSFORMS[name](args[1:])


if __name__ == '__main__':
	sys.exit(main())
