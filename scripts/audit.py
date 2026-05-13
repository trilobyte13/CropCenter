#!/usr/bin/env python3
"""Consolidated audit runner for the CropCenter Java sources.

Replaces five separate scripts (audit_final_classes.py, audit_ignored_catches.py,
audit_static_first.py, scan_over_120.py, count_lsloc.py) with a single CLI. Each
audit lives as a function inside this file; shared helpers (file walk, comment
strip) live at the top.

Usage:
    python scripts/audit.py                   # run all audits; exit code = sum of failures
    python scripts/audit.py <name> [roots...] # run one audit

Audit names:
    over-cols          lines exceeding 120 rendered columns (tab=8)
    ignored-catches    catches that swallow exceptions without explaining why
    static-first       static methods that follow instance methods in the same tier
    final-classes      classes that should be `final` per Effective Java item 19
    lsloc              logical SLOC count (UCC-style — excludes bare-brace lines)

Default roots: app/src/main/java app/src/test/java (or app/src for over-cols).
Exit code: 0 when all selected audits pass; 1 when any fails (lsloc never fails).
"""
import os
import re
import sys


# ─── shared helpers ──────────────────────────────────────────────────────────

def walk_java_files(roots):
	"""Yield (path, text) for every .java file under each root. UTF-8 read with
	`errors='replace'` so an adversarial byte sequence doesn't kill the walk."""
	for root in roots:
		for dp, _, fns in os.walk(root):
			for fn in fns:
				if fn.endswith('.java'):
					path = os.path.join(dp, fn)
					with open(path, encoding='utf-8', errors='replace') as f:
						yield path, f.read()


def strip_comments_strings(src):
	"""Remove block comments, line comments, and string/char literals while
	preserving newline counts. Used by the AST-ish audits (static-first,
	final-classes) so regex scans don't false-positive inside comments and
	literals. Multi-line block comments collapse to the same line-count of
	empty lines so line-numbers in regex hits stay accurate."""
	src = re.sub(r'/\*[\s\S]*?\*/', lambda m: '\n' * m.group(0).count('\n'), src)
	src = re.sub(r'//.*', '', src)
	src = re.sub(r'"(?:\\.|[^"\\])*"', '""', src)
	src = re.sub(r"'(?:\\.|[^'\\])*'", "''", src)
	return src


def rendered_width(line):
	"""Display columns for `line` with tabs expanding to width 8."""
	n = 0
	for c in line:
		if c == '\t':
			n = (n // 8) * 8 + 8
		else:
			n += 1
	return n


def access_of(mods):
	"""Map a modifier list (split tokens) to access tier name."""
	if 'public' in mods:
		return 'public'
	if 'protected' in mods:
		return 'protected'
	if 'private' in mods:
		return 'private'
	return 'package'


# ─── audit: over-cols ────────────────────────────────────────────────────────

def audit_over_cols(roots):
	"""Report .java lines exceeding 120 display columns (tab=8). Mirrors the
	awk one-liner in CLAUDE.md's Self-audit section but is PowerShell-safe.
	Returns exit code 0 (clean) or 1 (violations found)."""
	if not roots:
		roots = ['app/src']
	hits = 0
	for path, text in walk_java_files(roots):
		for ln, line in enumerate(text.split('\n'), 1):
			line = line.rstrip('\r')
			w = rendered_width(line)
			if w > 120:
				print(f'{path}:{ln} cols={w}')
				hits += 1
	print(f'\n{hits} line(s) over 120 cols')
	return 0 if hits == 0 else 1


# ─── audit: ignored-catches ──────────────────────────────────────────────────

_CATCH_RE = re.compile(r'^(?P<indent>\t*)catch\s*\((?P<sig>[^)]*)\)')


def _parse_catch_param(sig):
	"""Return the catch parameter's identifier ('e', 'ignored', etc) from the
	raw `(...)` body. Handles single-type, multi-catch (`A | B e`), and
	final-modifier forms."""
	tokens = sig.replace('|', ' ').split()
	return tokens[-1] if tokens else None


def _audit_catches_in_file(text):
	"""Yield (line_no, snippet, reason) for each violating catch in `text`.
	Relies on the project's strict Allman-brace + tabs-only style: for each
	`catch (...)` line at indent N, the opening `{` is the next non-blank
	line at indent N, and the matching close is the next line at indent N."""
	lines = text.split('\n')
	n = len(lines)
	violations = []
	i = 0
	while i < n:
		m = _CATCH_RE.match(lines[i])
		if not m:
			i += 1
			continue
		indent = m.group('indent')
		param = _parse_catch_param(m.group('sig'))
		j = i + 1
		while j < n and lines[j].strip() == '':
			j += 1
		if j >= n or lines[j] != indent + '{':
			i += 1
			continue
		close = indent + '}'
		body_has_comment = False
		body_has_statement = False
		k = j + 1
		while k < n and lines[k] != close:
			stripped = lines[k].lstrip('\t ')
			if stripped.startswith('//'):
				body_has_comment = True
			elif stripped:
				body_has_statement = True
			k += 1
		is_ignored = (param == 'ignored')
		is_empty = not body_has_statement and not body_has_comment
		if (is_ignored or is_empty) and not body_has_comment:
			reason = 'empty catch' if is_empty else "'ignored' catch missing // explanation"
			violations.append((i + 1, lines[i].lstrip('\t ').rstrip(), reason))
		i = k + 1
	return violations


def audit_ignored_catches(roots):
	"""Flag catches that swallow exceptions without an explanatory `//` comment.
	Catches that log or rethrow are out of scope (they already record the
	failure). See CLAUDE.md `Intentionally swallowed exceptions` rule for
	the contract."""
	if not roots:
		roots = ['app/src/main/java', 'app/src/test/java']
	all_violations = []
	file_count = 0
	for path, text in walk_java_files(roots):
		file_count += 1
		for line_no, snippet, reason in _audit_catches_in_file(text):
			all_violations.append((path, line_no, snippet, reason))
	if not all_violations:
		print(f'OK: {file_count} files scanned, '
			f'every ignored / empty catch carries an explanatory // comment.')
		return 0
	by_file = {}
	for path, line_no, snippet, reason in all_violations:
		by_file.setdefault(path, []).append((line_no, snippet, reason))
	for path, vs in sorted(by_file.items()):
		print(path)
		for line_no, snippet, reason in vs:
			print(f'  line {line_no} ({reason}): {snippet}')
		print()
	print(f'TOTAL: {len(all_violations)} swallowed catch(es) without an explanatory // comment '
		f'in {len(by_file)} file(s) (of {file_count} scanned)')
	return 1


# ─── audit: static-first ─────────────────────────────────────────────────────

_KW = {
	'class', 'interface', 'enum', 'record', 'if', 'while', 'for', 'switch',
	'try', 'catch', 'return', 'new', 'throw', 'do', 'else', 'synchronized',
	'super', 'this', 'assert', 'yield',
}

_METHOD_RE = re.compile(
	r'^\s*'
	r'(?P<mods>(?:(?:public|protected|private|static|final|abstract|synchronized|native|default)\s+)+)'
	r'(?:<[^>]+>\s+)?'
	r'(?:[\w.<>?\[\],\s]+?\s+)?'
	r'(?P<name>[A-Za-z_]\w*)\s*'
	r'\('
)

_DEFAULT_METHOD_RE = re.compile(
	r'^\s*'
	r'(?:<[^>]+>\s+)?'
	r'(?P<rt>[\w.<>?\[\],]+(?:\s*\[\s*\])*)\s+'
	r'(?P<name>[A-Za-z_]\w*)\s*'
	r'\('
)

_CLASS_RE = re.compile(r'\b(?:class|interface|enum|record|@interface)\s+([A-Z]\w*)')
_TYPE_DECL_RE = re.compile(r'\b(class|interface|enum|record|@interface)\s+[A-Z]')


def _audit_static_first_in_file(path, raw):
	"""Walk class scopes and report any static method that appears AFTER an
	instance method in the same (class, access-tier). Returns the violation
	list."""
	src = strip_comments_strings(raw)
	lines = src.split('\n')
	stack = []
	depth = 0
	pending_class = None
	violations = []
	for ln_idx, line in enumerate(lines):
		line_no = ln_idx + 1
		opens = line.count('{')
		closes = line.count('}')
		if stack and depth == stack[-1]['body_depth'] and not _TYPE_DECL_RE.search(line):
			m = _METHOD_RE.match(line)
			access = is_static = name = None
			if m and m.group('name') not in _KW:
				mods = m.group('mods').split()
				access = access_of(mods)
				is_static = 'static' in mods
				name = m.group('name')
			else:
				m2 = _DEFAULT_METHOD_RE.match(line)
				if m2 and m2.group('name') not in _KW:
					rt = m2.group('rt')
					if rt not in _KW and not rt.startswith('@'):
						access = 'package'
						is_static = False
						name = m2.group('name')
			if name is not None and name != stack[-1]['name']:
				cls = stack[-1]
				cls['methods'].setdefault(access, []).append({
					'line': line_no, 'name': name, 'is_static': is_static,
				})
		cls_m = _CLASS_RE.search(line)
		if cls_m:
			if opens > 0:
				stack.append({
					'name': cls_m.group(1), 'body_depth': depth + 1,
					'methods': {}, 'file': path,
				})
			else:
				pending_class = cls_m.group(1)
		elif pending_class is not None and opens > 0:
			stack.append({
				'name': pending_class, 'body_depth': depth + 1,
				'methods': {}, 'file': path,
			})
			pending_class = None
		depth += opens - closes
		while stack and depth < stack[-1]['body_depth']:
			cls = stack.pop()
			for access, methods in cls['methods'].items():
				seen_instance = None
				for entry in methods:
					if not entry['is_static']:
						if seen_instance is None:
							seen_instance = entry
					elif seen_instance is not None:
						violations.append({
							'file': cls['file'], 'class': cls['name'], 'access': access,
							'static_line': entry['line'], 'static_name': entry['name'],
							'instance_line': seen_instance['line'],
							'instance_name': seen_instance['name'],
						})
	return violations


def audit_static_first(roots):
	"""Enforce CLAUDE.md's `static methods come BEFORE instance methods of the
	same access level` rule. Reports class, access tier, and offending names."""
	if not roots:
		roots = ['app/src/main/java', 'app/src/test/java']
	all_violations = []
	file_count = 0
	for path, text in walk_java_files(roots):
		file_count += 1
		all_violations.extend(_audit_static_first_in_file(path, text))
	if not all_violations:
		print(f'OK: {file_count} files scanned, no static-after-instance violations.')
		return 0
	by_file = {}
	for v in all_violations:
		by_file.setdefault(v['file'], []).append(v)
	for path, vs in sorted(by_file.items()):
		print(path)
		for v in vs:
			print(f"  class {v['class']}  ({v['access']}):  "
				f"static '{v['static_name']}' (line {v['static_line']}) "
				f"appears after instance '{v['instance_name']}' "
				f"(line {v['instance_line']})")
		print()
	print(f'TOTAL: {len(all_violations)} violation(s) in '
		f'{len(by_file)} file(s) (of {file_count} scanned)')
	return 1


# ─── audit: final-classes ────────────────────────────────────────────────────

_CLASS_DECL_RE = re.compile(
	r'\b(?P<mods>(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed)\s+)*)'
	r'(?P<kind>class|interface|enum|record|@interface)\s+'
	r'(?P<name>[A-Z]\w*)'
)
_EXTENDS_RE = re.compile(r'\bextends\s+([A-Z]\w*)')
_ANON_NEW_RE = re.compile(r'\bnew\s+([A-Z]\w*)\s*(?:<[^>]*>\s*)?\([^)]*\)\s*\{')


def _scan_final_file(path, raw, extends_map, anon_map, decls):
	"""Collect class declarations + `extends X` + `new X() {` references into
	the shared maps so the cross-file analysis in audit_final_classes can
	classify candidates vs actually-extended classes."""
	src = strip_comments_strings(raw)
	for ln_idx, line in enumerate(src.split('\n')):
		line_no = ln_idx + 1
		for m in _CLASS_DECL_RE.finditer(line):
			mods = m.group('mods').split()
			decls.append({
				'name': m.group('name'), 'kind': m.group('kind'),
				'is_abstract': 'abstract' in mods, 'is_final': 'final' in mods,
				'is_sealed': 'sealed' in mods or 'non-sealed' in mods,
				'access': access_of(mods), 'file': path, 'line': line_no,
			})
		for m in _EXTENDS_RE.finditer(line):
			extends_map.setdefault(m.group(1), []).append((path, line_no))
		for m in _ANON_NEW_RE.finditer(line):
			anon_map.setdefault(m.group(1), []).append((path, line_no))


def audit_final_classes(roots):
	"""Identify classes that should be `final` per Effective Java item 19:
	concrete, non-final, never extended (`class X extends Foo` or
	`new Foo() { ... }`) anywhere in the codebase. Records / enums /
	interfaces / abstract / sealed classes are skipped."""
	if not roots:
		roots = ['app/src/main/java', 'app/src/test/java']
	decls = []
	extends_map = {}
	anon_map = {}
	file_count = 0
	for path, text in walk_java_files(roots):
		file_count += 1
		_scan_final_file(path, text, extends_map, anon_map, decls)
	extended = set(extends_map.keys()) | set(anon_map.keys())
	candidates = []
	extended_classes = []
	for d in decls:
		if d['kind'] != 'class':
			continue
		if d['is_abstract'] or d['is_final'] or d['is_sealed']:
			continue
		if d['name'] in extended:
			extended_classes.append(d)
		else:
			candidates.append(d)
	candidates.sort(key=lambda d: (d['file'], d['line']))
	extended_classes.sort(key=lambda d: (d['file'], d['line']))
	print('=' * 72)
	print(f'SHOULD BE FINAL  ({len(candidates)} class(es))')
	print('Not abstract, not already final, not extended anywhere:')
	print('=' * 72)
	last_file = None
	for d in candidates:
		if d['file'] != last_file:
			print()
			print(d['file'])
			last_file = d['file']
		print(f"  L{d['line']:>4}  {d['access']:>9} class {d['name']}")
	print()
	print('=' * 72)
	print(f'EXTENDED  ({len(extended_classes)} class(es))')
	print('Currently non-final, but actually subclassed - leave as-is:')
	print('=' * 72)
	last_file = None
	for d in extended_classes:
		if d['file'] != last_file:
			print()
			print(d['file'])
			last_file = d['file']
		sub_files = []
		for src, ln in extends_map.get(d['name'], []):
			sub_files.append(f'{os.path.basename(src)}:{ln} (extends)')
		for src, ln in anon_map.get(d['name'], []):
			sub_files.append(f'{os.path.basename(src)}:{ln} (anon)')
		print(f"  L{d['line']:>4}  {d['access']:>9} class {d['name']}")
		for s in sub_files:
			print(f'            -> {s}')
	print()
	print(f'Scanned {file_count} files.')
	print(f'Total class declarations: {sum(1 for d in decls if d["kind"] == "class")}')
	print(f'  abstract:  {sum(1 for d in decls if d["kind"] == "class" and d["is_abstract"])}')
	print(f'  final:     {sum(1 for d in decls if d["kind"] == "class" and d["is_final"])}')
	print(f'  sealed:    {sum(1 for d in decls if d["kind"] == "class" and d["is_sealed"])}')
	print(f'  extended:  {len(extended_classes)}')
	print(f'  recommend: {len(candidates)}')
	return 0 if len(candidates) == 0 else 1


# ─── audit: lsloc ────────────────────────────────────────────────────────────

_STRUCTURAL_ONLY = re.compile(r'^[\{\}\(\),;\s]+$')


def _count_lsloc(text):
	"""UCC-style logical-SLOC count: lines with code that isn't purely
	structural delimiters. Strips line + block comments first; then skips
	blank lines and lines that contain only braces, parens, commas, and
	semicolons (structural closers of a statement already counted upstream)."""
	text = re.sub(r'/\*.*?\*/', '', text, flags=re.DOTALL)
	count = 0
	for raw in text.split('\n'):
		line = re.sub(r'//.*$', '', raw).strip()
		if not line:
			continue
		if _STRUCTURAL_ONLY.match(line):
			continue
		count += 1
	return count


def audit_lsloc(roots):
	"""Report logical-SLOC totals per root and combined. Always returns 0 —
	this is a metric, not a pass/fail audit. Useful to refresh the count in
	REQUIREMENTS.md when the codebase grows."""
	if not roots:
		roots = ['app/src/main/java', 'app/src/test/java']
	grand_files = grand_phys = grand_lsloc = 0
	for root in roots:
		if not os.path.isdir(root):
			print(f'  {root}: (not found)')
			continue
		files = phys = lsloc = 0
		for path, text in walk_java_files([root]):
			files += 1
			phys += text.count('\n') + (1 if text and not text.endswith('\n') else 0)
			lsloc += _count_lsloc(text)
		print(f'  {root}: {files} files, {phys} physical lines, {lsloc} LSLOC')
		grand_files += files
		grand_phys += phys
		grand_lsloc += lsloc
	if len(roots) > 1:
		print(f'\n  TOTAL: {grand_files} files, {grand_phys} physical lines, {grand_lsloc} LSLOC')
	return 0


# ─── dispatcher ──────────────────────────────────────────────────────────────

AUDITS = {
	'over-cols': audit_over_cols,
	'ignored-catches': audit_ignored_catches,
	'static-first': audit_static_first,
	'final-classes': audit_final_classes,
	'lsloc': audit_lsloc,
}


def main():
	args = sys.argv[1:]
	if args and args[0] in ('-h', '--help'):
		print(__doc__)
		return 0
	if not args:
		# Run all audits in sequence. Exit code = 1 if any failed.
		total_rc = 0
		for name, fn in AUDITS.items():
			print(f'\n{"=" * 8} {name} {"=" * 8}\n')
			rc = fn([])
			if rc != 0:
				total_rc = 1
		return total_rc
	name = args[0]
	if name not in AUDITS:
		print(f'unknown audit: {name}', file=sys.stderr)
		print(f'available: {", ".join(AUDITS.keys())}', file=sys.stderr)
		return 2
	return AUDITS[name](args[1:])


if __name__ == '__main__':
	sys.exit(main())
