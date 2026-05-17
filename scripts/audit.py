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
    over-cols                 lines exceeding 120 rendered columns (tab=8)
    ignored-catches           catches that swallow exceptions without explaining why
    static-first              static methods that follow instance methods in the same tier
    method-order              public→protected→package→private tier order + case-sensitive alphabetical within tier
    adjacent-comment-styles   `*/` immediately followed by `//` (consolidate into Javadoc)
    final-classes             classes that should be `final` per Effective Java item 19
    reflow                    multi-line comment blocks whose last line could fold into the prior
    lsloc                     logical SLOC count (UCC-style — excludes bare-brace lines)

Default roots: app/src/main/java app/src/test/java (or app/src for over-cols).
Exit code: 0 when all selected audits pass; 1 when any fails (reflow and lsloc never fail —
both are advisory metrics, not pass/fail style violations).
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


# ─── audit: method-order ─────────────────────────────────────────────────────

_ACCESS_RANK = {'public': 1, 'protected': 2, 'package': 3, 'private': 4}
_SECTION_DIVIDER_RE = re.compile(r'^\s*//\s*[─=]{2,}|^\s*//.*[─=]{2,}\s*$')


def _audit_method_order_in_file(path, raw):
	"""Walk each class scope and report:
	  - access-tier inversions (private method appearing before package-private, etc.)
	  - case-sensitive alphabetical violations within each (access, static/instance) sub-block
	Section-divider comments (`// ── X ──`, `// ── X` etc.) reset the alphabetical-order
	baseline so deliberate thematic groupings inside one tier don't false-positive."""
	src = strip_comments_strings(raw)
	raw_lines = raw.split('\n')
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
				cls['methods'].append({
					'line': line_no, 'name': name, 'access': access, 'is_static': is_static,
					'divider_before': cls['saw_divider_since_last_method'],
				})
				cls['saw_divider_since_last_method'] = False
		# Check raw line for section dividers (block comments were stripped from `src`).
		if stack and _SECTION_DIVIDER_RE.match(raw_lines[ln_idx]):
			stack[-1]['saw_divider_since_last_method'] = True
		cls_m = _CLASS_RE.search(line)
		if cls_m:
			if opens > 0:
				stack.append({
					'name': cls_m.group(1), 'body_depth': depth + 1,
					'methods': [], 'saw_divider_since_last_method': False, 'file': path,
				})
			else:
				pending_class = cls_m.group(1)
		elif pending_class is not None and opens > 0:
			stack.append({
				'name': pending_class, 'body_depth': depth + 1,
				'methods': [], 'saw_divider_since_last_method': False, 'file': path,
			})
			pending_class = None
		depth += opens - closes
		while stack and depth < stack[-1]['body_depth']:
			cls = stack.pop()
			methods = cls['methods']
			# Access-tier inversion: each method's rank must be >= the previous method's rank.
			# A drop from rank K to rank L < K is an inversion (e.g., private at rank 4 followed
			# by package at rank 3).
			for i in range(1, len(methods)):
				prev = methods[i - 1]
				cur = methods[i]
				prev_rank = _ACCESS_RANK.get(prev['access'], 99)
				cur_rank = _ACCESS_RANK.get(cur['access'], 99)
				if cur_rank < prev_rank:
					violations.append({
						'kind': 'tier-inversion', 'file': cls['file'], 'class': cls['name'],
						'prev_name': prev['name'], 'prev_line': prev['line'],
						'prev_access': prev['access'],
						'cur_name': cur['name'], 'cur_line': cur['line'],
						'cur_access': cur['access'],
					})
			# Alphabetical-within-sub-block: within each (access, static/instance) bucket, names
			# must be case-sensitive non-decreasing in ASCII order. Section dividers reset the
			# baseline so HorizonDetector / ByteBufferUtils-style thematic groupings are exempt.
			buckets = {}
			for m in methods:
				key = (m['access'], m['is_static'])
				buckets.setdefault(key, []).append(m)
			for key, bucket in buckets.items():
				prev_name = None
				exempt_remaining = False
				for entry in bucket:
					if entry['divider_before']:
						# A section divider (// ── X ──) inside a bucket signals manual
						# organization (thematic grouping over alphabetical). HorizonDetector's
						# pipeline order and ByteBufferUtils's endian split rely on this. Once a
						# divider intervenes, exempt the rest of the bucket from alphabetical
						# checks rather than just resetting the baseline — partial alphabetical
						# inside a divider-grouped section is more confusing than useful.
						exempt_remaining = True
					if not exempt_remaining:
						if prev_name is not None and entry['name'] < prev_name:
							violations.append({
								'kind': 'alphabetical', 'file': cls['file'],
								'class': cls['name'], 'access': key[0],
								'is_static': key[1], 'prev_name': prev_name,
								'cur_name': entry['name'], 'cur_line': entry['line'],
							})
					prev_name = entry['name']
	return violations


def audit_method_order(roots):
	"""Enforce CLAUDE.md's method-ordering rules beyond static-first: access-tier order
	(public → protected → package-private → private) and case-sensitive alphabetical
	ordering within each (access, static/instance) sub-block. Section dividers
	(// ── ... ──) reset the alphabetical baseline within a tier so thematic
	groupings stay exempt."""
	if not roots:
		roots = ['app/src/main/java', 'app/src/test/java']
	all_violations = []
	file_count = 0
	for path, text in walk_java_files(roots):
		file_count += 1
		all_violations.extend(_audit_method_order_in_file(path, text))
	if not all_violations:
		print(f'OK: {file_count} files scanned, no method-order violations.')
		return 0
	by_file = {}
	for v in all_violations:
		by_file.setdefault(v['file'], []).append(v)
	for path, vs in sorted(by_file.items()):
		print(path)
		for v in vs:
			if v['kind'] == 'tier-inversion':
				print(f"  class {v['class']}:  {v['cur_access']} '{v['cur_name']}' "
					f"(line {v['cur_line']}) appears after {v['prev_access']} "
					f"'{v['prev_name']}' (line {v['prev_line']}) — "
					f"expected order public -> protected -> package -> private")
			else:
				static_str = 'static' if v['is_static'] else 'instance'
				print(f"  class {v['class']}:  {v['access']} {static_str} '{v['cur_name']}' "
					f"(line {v['cur_line']}) follows '{v['prev_name']}' -- "
					f"case-sensitive ASCII alphabetical expected")
		print()
	print(f'TOTAL: {len(all_violations)} violation(s) in {len(by_file)} file(s) '
		f'(of {file_count} scanned)')
	return 1


# ─── audit: adjacent-comment-styles ──────────────────────────────────────────

def _audit_adjacent_comments_in_file(text):
	"""Yield (line_no, snippet) for each `*/` line followed by a `//` line. The
	anti-pattern is a Javadoc block sitting immediately above a `//` rationale —
	these should be consolidated by folding the `//` content into the Javadoc."""
	lines = text.split('\n')
	hits = []
	for i in range(len(lines) - 1):
		cur = lines[i].rstrip()
		nxt = lines[i + 1].lstrip()
		if cur.endswith('*/') and nxt.startswith('//'):
			hits.append((i + 1, cur.lstrip('\t ')))
	return hits


def audit_adjacent_comment_styles(roots):
	"""Flag `*/` (closing block / Javadoc comment) immediately followed by `//` line
	comment. The pattern means a rationale comment got tacked on AFTER the Javadoc
	rather than folded INTO it — per CLAUDE.md the Javadoc should absorb the `//`
	content as a new paragraph (separated by `*` on its own line)."""
	if not roots:
		roots = ['app/src/main/java', 'app/src/test/java']
	all_violations = []
	file_count = 0
	for path, text in walk_java_files(roots):
		file_count += 1
		for line_no, snippet in _audit_adjacent_comments_in_file(text):
			all_violations.append((path, line_no, snippet))
	if not all_violations:
		print(f'OK: {file_count} files scanned, no adjacent-comment-style violations.')
		return 0
	by_file = {}
	for path, line_no, snippet in all_violations:
		by_file.setdefault(path, []).append((line_no, snippet))
	for path, vs in sorted(by_file.items()):
		print(path)
		for line_no, snippet in vs:
			print(f"  line {line_no}: {snippet}  followed by '//' line — fold into Javadoc")
		print()
	print(f'TOTAL: {len(all_violations)} adjacent-comment-style violation(s) in '
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


# ─── audit: reflow ───────────────────────────────────────────────────────────

def _text_after_marker(line, marker):
	"""Return the body text of a comment line after `marker` (`*` for Javadoc body lines,
	`//` for inline). One leading space after the marker is consumed if present."""
	idx = line.find(marker)
	if idx < 0:
		return None
	after = line[idx + len(marker):]
	if after.startswith(' '):
		after = after[1:]
	return after


def _scan_javadoc_blocks(lines):
	"""Find Javadoc blocks whose LAST body line is short enough that combining with the prior body
	line stays under 120 cols. Excludes empty `*` lines (paragraph separators) and `@param`/`@return`
	tag lines."""
	hits = []
	i = 0
	while i < len(lines):
		stripped = lines[i].lstrip('\t ')
		if stripped.startswith('/**'):
			body_lines = []
			j = i + 1
			while j < len(lines):
				bs = lines[j].lstrip('\t ')
				if bs.startswith('*/'):
					break
				if bs.startswith('*'):
					rest = bs[1:].lstrip()
					body_lines.append((j, lines[j], rest))
				j += 1
			# Find the last non-empty, non-tag line
			last_text_idx = None
			for k in range(len(body_lines) - 1, -1, -1):
				_, _, txt = body_lines[k]
				if not txt or txt.startswith('@'):
					continue
				last_text_idx = k
				break
			if last_text_idx is not None and last_text_idx > 0:
				prev_idx = last_text_idx - 1
				_, _, prev_txt = body_lines[prev_idx]
				if prev_txt and not prev_txt.startswith('@'):
					ln_no, last_line_full, last_txt = body_lines[last_text_idx]
					last_rendered = rendered_width(last_line_full.rstrip('\n'))
					if last_rendered < 80:
						_, prev_line_full, _ = body_lines[prev_idx]
						prev_rendered = rendered_width(prev_line_full.rstrip('\n'))
						combined = prev_rendered + 1 + len(last_txt)
						if combined <= 120 and last_rendered < prev_rendered:
							hits.append((ln_no + 1, prev_line_full.rstrip(),
								last_line_full.rstrip(), combined))
			i = j + 1
			continue
		i += 1
	return hits


def _scan_inline_blocks(lines):
	"""Find consecutive `//` comment blocks whose last line could fold into the prior. Section
	dividers (`//──`, `// ──`) are excluded at BOTH the outer guard and the inner loop — a previous
	version that excluded only `//─` looped forever on `// ── section ──` lines (re-entered the
	same line because the outer guard accepted it but the inner guard broke immediately, producing
	a `continue` without advancing `i`)."""
	hits = []
	i = 0
	while i < len(lines):
		stripped = lines[i].lstrip('\t ')
		if (stripped.startswith('//')
				and not stripped.startswith('///')
				and not stripped.startswith('//─')
				and not stripped.startswith('// ──')):
			block = []
			while i < len(lines):
				bs = lines[i].lstrip('\t ')
				if not bs.startswith('//'):
					break
				if bs.startswith('//─') or bs.startswith('// ──'):
					break
				block.append((i, lines[i]))
				i += 1
			if len(block) >= 2:
				_, prev_full = block[-2]
				last_ln, last_full = block[-1]
				last_rendered = rendered_width(last_full.rstrip('\n'))
				prev_rendered = rendered_width(prev_full.rstrip('\n'))
				last_text = _text_after_marker(last_full, '//')
				if last_text and last_rendered < 80 and last_rendered < prev_rendered:
					combined = prev_rendered + 1 + len(last_text.rstrip())
					if combined <= 120:
						hits.append((last_ln + 1, prev_full.rstrip(),
							last_full.rstrip(), combined))
			continue
		i += 1
	return hits


def audit_reflow(roots):
	"""Report multi-line comment blocks (Javadoc and `//`) whose last body line ends short and
	could fold into the prior line under 120 cols. Catches the typical aftermath of a bulk strip —
	a sentence that originally wrapped tightly now has its tail orphaned on a short last line.
	Always returns 0; this is an advisory metric, not a pass/fail audit (reflow opportunities are
	style suggestions, not violations)."""
	if not roots:
		roots = ['app/src/main/java', 'app/src/test/java']
	count = 0
	for path, text in walk_java_files(roots):
		lines = text.split('\n')
		for ln, prev, last, combined in _scan_javadoc_blocks(lines):
			print(f'{path}:{ln} (javadoc, combined {combined} cols)')
			print(f'  prev: {prev}')
			print(f'  last: {last}')
			print()
			count += 1
		for ln, prev, last, combined in _scan_inline_blocks(lines):
			print(f'{path}:{ln} (inline, combined {combined} cols)')
			print(f'  prev: {prev}')
			print(f'  last: {last}')
			print()
			count += 1
	print(f'Total reflow candidates: {count}')
	return 0


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
	'method-order': audit_method_order,
	'adjacent-comment-styles': audit_adjacent_comment_styles,
	'final-classes': audit_final_classes,
	'reflow': audit_reflow,
	'lsloc': audit_lsloc,
}


def main():
	# Reconfigure stdout/stderr to UTF-8 with replacement on Windows where the default console
	# encoding is cp1252 / cp437. The `reflow` audit prints raw source-line snippets that contain
	# Unicode characters (em-dashes, arrows, the `─` section-divider character). Without this,
	# `python scripts/audit.py` on Windows raises UnicodeEncodeError mid-reflow and never reaches
	# the `lsloc` step — defeating CLAUDE.md's documented "cross-shell self-audit runner" promise.
	# `reconfigure` is Python 3.7+; CropCenter pins ≥ 3.9, so the call is always safe. The
	# try/except handles the edge case where stdout has been rewrapped to a non-text stream
	# (unusual but theoretically possible when callers pipe to a subprocess that captures bytes).
	try:
		sys.stdout.reconfigure(encoding='utf-8', errors='replace')
		sys.stderr.reconfigure(encoding='utf-8', errors='replace')
	except (AttributeError, OSError):
		pass

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
