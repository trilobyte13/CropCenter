#!/usr/bin/env python3
"""Consolidated audit runner for the CropCenter Java sources.

Single CLI for every audit: each audit lives as a function inside this file; shared helpers (file walk, comment
strip) live at the top.

Usage:
    python scripts/audit.py                   # run all audits; exit code = sum of failures
    python scripts/audit.py <name> [roots...] # run one audit
    python scripts/audit.py selftest          # self-check the stripper, comma-join scanner, py + code reflow

Audit names:
    over-cols                 lines exceeding 120 rendered columns (tab=8)
    over-cols-py              scripts/*.py lines exceeding 120 rendered columns (advisory)
    ignored-catches           catches that swallow exceptions without explaining why
    static-first              static methods that follow instance methods in the same tier
    method-order              public→protected→package→private tier order + case-sensitive alphabetical within tier
    adjacent-comment-styles   `*/` immediately followed by `//` (consolidate into Javadoc)
    final-classes             classes that should be `final` per Effective Java item 19
    reflow                    comment blocks whose last line could fold into the prior, and
                              comma-wrapped constructs that would fit 120 cols joined
    lsloc                     logical SLOC count (UCC-style — excludes bare-brace lines)

Default roots: app/src/main/java app/src/test/java (app/src for over-cols; scripts for over-cols-py).
Exit code: 0 when all selected audits pass; 1 when any fails (over-cols-py, reflow, and lsloc never
fail — all three are advisory metrics, not pass/fail style violations).
"""
import glob
import os
import re
import sys


# ─── shared helpers ──────────────────────────────────────────────────────────

def walk_java_files(roots):
	"""Yield (path, text) for every .java file under each root. UTF-8 read with `errors='replace'` so an adversarial
	byte sequence doesn't kill the walk."""
	for root in roots:
		for dp, _, fns in os.walk(root):
			for fn in fns:
				if fn.endswith('.java'):
					path = os.path.join(dp, fn)
					with open(path, encoding='utf-8', errors='replace') as f:
						yield path, f.read()


def strip_comments_strings(src):
	"""Remove block comments and line comments, and collapse string / char literals to empty ("" / ''), preserving
	newline counts throughout so the AST-ish audits (static-first, method-order, final-classes) see accurate line
	numbers and never false-positive on braces or keywords inside comments and literals. Single left-to-right scan
	with explicit state, so a // or /* INSIDE a string (http:// and content:// URLs) never starts a comment and an
	escaped quote never ends a literal early — a regex pipeline that strips comments before strings mis-pairs the
	quotes around such URLs and silently swallows multi-line regions. Java 21 text blocks collapse to "" plus their
	interior newlines. A literal left unterminated at end-of-line (impossible in valid Java, reachable via
	errors='replace' mojibake) closes at the newline so damage never crosses lines."""
	out = []
	i = 0
	n = len(src)
	while i < n:
		c = src[i]
		if c == '/' and i + 1 < n and src[i + 1] == '/':
			# Line comment: drop text, keep the newline (emitted by the outer loop).
			i += 2
			while i < n and src[i] != '\n':
				i += 1
		elif c == '/' and i + 1 < n and src[i + 1] == '*':
			# Block comment: collapse to its newline count.
			i += 2
			while i + 1 < n and (src[i] != '*' or src[i + 1] != '/'):
				if src[i] == '\n':
					out.append('\n')
				i += 1
			i = min(i + 2, n)
		elif c == '"' and src.startswith('"""', i):
			# Text block: collapse to "" plus interior newlines.
			out.append('""')
			i += 3
			while i < n and not src.startswith('"""', i):
				if src[i] == '\\' and i + 1 < n and src[i + 1] != '\n':
					i += 2
				else:
					if src[i] == '\n':
						out.append('\n')
					i += 1
			i = min(i + 3, n)
		elif c == '"' or c == "'":
			# String / char literal: collapse to an empty pair. A backslash escapes the next char unless
			# that char is a newline (which the outer loop must emit to keep line counts exact).
			out.append(c + c)
			i += 1
			while i < n and src[i] != c and src[i] != '\n':
				if src[i] == '\\' and i + 1 < n and src[i + 1] != '\n':
					i += 2
				else:
					i += 1
			if i < n and src[i] == c:
				i += 1
		else:
			out.append(c)
			i += 1
	return ''.join(out)


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
	"""Report .java lines exceeding 120 display columns (tab=8). Mirrors the awk one-liner in CLAUDE.md's Self-audit
	section but is PowerShell-safe. Returns exit code 0 (clean) or 1 (violations found)."""
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


# ─── audit: over-cols-py ─────────────────────────────────────────────────────

def audit_over_cols_py(roots):
	"""Advisory twin of over-cols for the Python tooling: report scripts/*.py lines exceeding 120 display columns
	(tab=8) so drift in the scripts prints alongside the Java checks. Fix by running `python scripts/refactor.py py`
	(then manually fixing whatever its MANUAL FIX report prints). Always returns 0 — advisory, like reflow / lsloc.
	"""
	if not roots:
		roots = ['scripts']
	hits = 0
	for root in roots:
		paths = sorted(glob.glob(os.path.join(root, '*.py'))) if os.path.isdir(root) else [root]
		for path in paths:
			with open(path, encoding='utf-8', errors='replace') as f:
				text = f.read()
			for ln, line in enumerate(text.split('\n'), 1):
				w = rendered_width(line.rstrip('\r'))
				if w > 120:
					print(f'{path}:{ln} cols={w}')
					hits += 1
	print(f'\n{hits} python line(s) over 120 cols')
	return 0


# ─── audit: ignored-catches ──────────────────────────────────────────────────

_CATCH_RE = re.compile(r'^(?P<indent>\t*)catch\s*\((?P<sig>[^)]*)\)')


def _parse_catch_param(sig):
	"""Return the catch parameter's identifier ('e', 'ignored', etc) from the raw `(...)` body. Handles single-type,
	multi-catch (`A | B e`), and final-modifier forms."""
	tokens = sig.replace('|', ' ').split()
	return tokens[-1] if tokens else None


def _audit_catches_in_file(text):
	"""Yield (line_no, snippet, reason) for each violating catch in `text`. Relies on the project's strict
	Allman-brace + tabs-only style: for each `catch (...)` line at indent N, the opening `{` is the next non-blank
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
	"""Flag catches that swallow exceptions without an explanatory `//` comment. Catches that log or rethrow are out
	of scope (they already record the failure). See CLAUDE.md `Intentionally swallowed exceptions` rule for the
	contract."""
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
	"""Walk class scopes and report any static method that appears AFTER an instance method in the same (class,
	access-tier). Returns the violation list."""
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
	"""Enforce CLAUDE.md's `static methods come BEFORE instance methods of the same access level` rule. Reports
	class, access tier, and offending names."""
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
			# Access-tier inversion: each method's rank must be >= the previous method's rank. A drop from
			# rank K to rank L < K is an inversion (e.g., private at rank 4 followed by package at rank 3).
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
			# Alphabetical-within-sub-block: within each (access, static/instance) bucket, names must be
			# case-sensitive non-decreasing in ASCII order. Section dividers reset the baseline so
			# HorizonDetector / ByteBufferUtils-style thematic groupings are exempt.
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
	"""Enforce CLAUDE.md's method-ordering rules beyond static-first: access-tier order (public → protected →
	package-private → private) and case-sensitive alphabetical ordering within each (access, static/instance)
	sub-block. Section dividers (// ── ... ──) reset the alphabetical baseline within a tier so thematic groupings
	stay exempt."""
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
	"""Yield (line_no, snippet) for each `*/` line followed by a `//` line. The anti-pattern is a Javadoc block
	sitting immediately above a `//` rationale — these should be consolidated by folding the `//` content into the
	Javadoc."""
	lines = text.split('\n')
	hits = []
	for i in range(len(lines) - 1):
		cur = lines[i].rstrip()
		nxt = lines[i + 1].lstrip()
		if cur.endswith('*/') and nxt.startswith('//'):
			hits.append((i + 1, cur.lstrip('\t ')))
	return hits


def audit_adjacent_comment_styles(roots):
	"""Flag `*/` (closing block / Javadoc comment) immediately followed by `//` line comment. The pattern means a
	rationale comment got tacked on AFTER the Javadoc rather than folded INTO it — per CLAUDE.md the Javadoc should
	absorb the `//` content as a new paragraph (separated by `*` on its own line)."""
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
	"""Collect class declarations + `extends X` + `new X() {` references into the shared maps so the cross-file
	analysis in audit_final_classes can classify candidates vs actually-extended classes."""
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
	"""Return the body text of a comment line after `marker` (`*` for Javadoc body lines, `//` for inline). One
	leading space after the marker is consumed if present."""
	idx = line.find(marker)
	if idx < 0:
		return None
	after = line[idx + len(marker):]
	if after.startswith(' '):
		after = after[1:]
	return after


def _scan_javadoc_blocks(lines):
	"""Find Javadoc blocks whose LAST body line is short enough that combining with the prior body line stays under
	120 cols. Excludes empty `*` lines (paragraph separators) and `@param`/`@return` tag lines."""
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
	"""Find consecutive `//` comment blocks whose last line could fold into the prior. Section dividers (`//──`, `//
	──`) are excluded at BOTH the outer guard and the inner loop — excluding at only one of the two loops forever
	re-enters the same `// ── section ──` line (the outer guard accepts it, the inner guard breaks immediately, and
	the `continue` never advances `i`)."""
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


# ── comma-continuation drift detection ──
#
# Mirrors refactor.py's whole-construct comma join: a code line ending with ',' whose complete
# construct (continuation lines consumed until one ends without ',') would fit 120 display
# columns joined. Same exclusions, each matching a CLAUDE.md rule: (a) rows inside brace-opened
# array / collection initializers (`= {`, Allman `=` + `{`, `new type[] {`), tracked by brace
# context — K&R multi-line initializers and fixture data grids are deliberate semantic layouts;
# (b) comment / Javadoc lines and trailing comments; (c) annotation lines; (d) `case` /
# `default` labels; (e) enum constant lists; (f) the pairwise-join skips (chains, ternary,
# string-literal spans, Allman braces); (g) Java text-block interiors, tracked by a per-line
# `"""` state scan — the lines between the delimiters are string content, not joinable code.
# Advisory only — future drift prints alongside the comment-fold forms so
# `python scripts/refactor.py code` can be re-run deliberately.

_ARRAY_NEW_OPEN = re.compile(r'\bnew\s+[\w$.]+\s*(?:\[\s*\])+\s*$')
_ENUM_DECL = re.compile(r'\benum\s+\w')
_CASE_LABEL = re.compile(r'^\s*(?:case\b|default\b)')


def _split_code_comment(line):
	"""Return (code, has_comment) — `code` is the line with string / char literal contents emptied and any `//` tail
	removed; `has_comment` is True when the line carries a `//` or `/*` comment outside a literal."""
	out = []
	has_comment = False
	i = 0
	n = len(line)
	while i < n:
		c = line[i]
		if c == '/' and i + 1 < n and line[i + 1] in '/*':
			has_comment = True
			break
		if c == '"' or c == "'":
			quote = c
			out.append(c)
			i += 1
			while i < n and line[i] != quote:
				if line[i] == '\\':
					i += 1
				i += 1
			if i < n:
				out.append(quote)
				i += 1
			continue
		out.append(c)
		i += 1
	return ''.join(out), has_comment


def _ends_in_string_literal(line):
	"""Best-effort odd-quote count outside of `//` line comments — True when the line ends inside a string literal
	(a joined line would corrupt the span)."""
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


def _text_block_flags(lines):
	"""Per-line booleans marking every line any part of which lies inside a Java text block (triple-quote
	delimited). Text-block content is string data, not code — an interior line ending with ',' must never be treated
	as a joinable comma construct (joining would rewrite the literal's runtime value). Quotes inside line comments
	and ordinary string / char literals never open a block; inside a block a backslash escapes the next character,
	so an escaped quote never closes one. Comment-shaped lines are skipped while outside a block (a Javadoc example
	containing the delimiter must not flip the state)."""
	flags = [False] * len(lines)
	in_block = False
	for idx, line in enumerate(lines):
		touched = in_block
		if not in_block and line.lstrip().startswith(('//', '*', '/*')):
			continue
		i = 0
		n = len(line)
		while i < n:
			if in_block:
				if line[i] == '\\' and i + 1 < n:
					i += 2
				elif line.startswith('"""', i):
					in_block = False
					touched = True
					i += 3
				else:
					i += 1
			elif line[i] == '/' and i + 1 < n and line[i + 1] in '/*':
				break
			elif line.startswith('"""', i):
				in_block = True
				touched = True
				i += 3
			elif line[i] == '"' or line[i] == "'":
				quote = line[i]
				i += 1
				while i < n and line[i] != quote:
					if line[i] == '\\':
						i += 1
					i += 1
				i += 1
			else:
				i += 1
		flags[idx] = touched or in_block
	return flags


def _comma_construct_at(lines, i, text_block_flags):
	"""Scan the comma construct starting at line i (already known to end with ','). Returns (end_idx, joined) where
	end_idx is the construct's terminator line and `joined` is the single-line form when the construct could join,
	else None. A construct touching a text-block line never joins."""
	parts = []
	j = i
	ok = True
	n = len(lines)
	while True:
		line = lines[j]
		code, has_comment = _split_code_comment(line)
		stripped = code.strip()
		lstripped = line.lstrip()
		if (not stripped or has_comment or '{' in code or '}' in code
				or lstripped.startswith(('//', '*', '/*'))
				or _ends_in_string_literal(line) or text_block_flags[j]):
			ok = False
		if j == i and (lstripped.startswith('@') or _CASE_LABEL.match(line)):
			ok = False
		if j > i and lstripped.startswith(('.', '?', ':')):
			ok = False
		parts.append(line)
		if not code.rstrip().endswith(','):
			break
		j += 1
		if j >= n:
			return j - 1, None
	if not ok:
		return j, None
	if j + 1 < n and lines[j + 1].lstrip().startswith(('.', '?', ':')):
		# Statement continues past the terminator in a skipped form (chain / ternary).
		return j, None
	joined = parts[0].rstrip()
	for part in parts[1:]:
		piece = part.strip()
		sep = '' if piece.startswith(')') else ' '
		joined = joined + sep + piece
	if rendered_width(joined) > 120:
		return j, None
	return j, joined


def _scan_comma_joins(lines):
	"""Return [(start_idx, end_idx, joined)] for every comma construct that could join under 120 cols. Tracks brace
	context so rows inside array / collection initializers and enum bodies are never candidates, and text-block
	state so a text block's interior lines are never candidates (nor brace context — their braces are string data);
	a rejected construct's interior comma lines are never re-tried as fresh construct starts (that would report
	exactly the partial join the whole-construct rule exists to avoid)."""
	hits = []
	ctx = []
	pending = None
	skip_detection_until = -1
	text_block_flags = _text_block_flags(lines)
	for i, line in enumerate(lines):
		if text_block_flags[i]:
			continue
		code, _ = _split_code_comment(line)
		stripped = code.strip()
		if (i > skip_detection_until and stripped.endswith(',')
				and not (ctx and ctx[-1] in ('init', 'enum'))
				and not line.lstrip().startswith(('//', '*', '/*'))):
			end_idx, joined = _comma_construct_at(lines, i, text_block_flags)
			if joined is not None:
				hits.append((i, end_idx, joined))
			skip_detection_until = end_idx
		for idx, c in enumerate(code):
			if c == '{':
				prefix = code[:idx].rstrip()
				if not prefix:
					if pending in ('init', 'enum'):
						kind = pending
					elif ctx and ctx[-1] == 'init':
						kind = 'init'
					else:
						kind = 'block'
				elif ctx and ctx[-1] == 'init':
					kind = 'init'
				elif prefix.endswith('='):
					kind = 'init'
				elif _ARRAY_NEW_OPEN.search(prefix):
					kind = 'init'
				elif _ENUM_DECL.search(prefix):
					kind = 'enum'
				else:
					kind = 'block'
				ctx.append(kind)
			elif c == '}':
				if ctx:
					ctx.pop()
		if '{' in code or '}' in code:
			pending = None
		elif stripped:
			if stripped.endswith('='):
				pending = 'init'
			elif _ENUM_DECL.search(stripped):
				pending = 'enum'
			else:
				pending = None
	return hits


def audit_reflow(roots):
	"""Report multi-line comment blocks (Javadoc and `//`) whose last body line ends short and could fold into the
	prior line under 120 cols, plus comma-continuation constructs that would fit 120 cols joined (mirroring
	refactor.py's code join, same exclusions — see the comma-continuation section above). Catches the typical
	aftermath of a bulk strip — a sentence that originally wrapped tightly now has its tail orphaned on a short last
	line — and future comma-wrap drift. Always returns 0; this is an advisory metric, not a pass/fail audit (reflow
	opportunities are style suggestions, not violations)."""
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
		for start, end, joined in _scan_comma_joins(lines):
			print(f'{path}:{start + 1} (comma, joined {rendered_width(joined)} cols)')
			print(f'  first: {lines[start].rstrip()}')
			print(f'  last:  {lines[end].rstrip()}')
			print()
			count += 1
	print(f'Total reflow candidates: {count}')
	return 0


# ─── audit: lsloc ────────────────────────────────────────────────────────────

_STRUCTURAL_ONLY = re.compile(r'^[\{\}\(\),;\s]+$')


def _count_lsloc(text):
	"""UCC-style logical-SLOC count: lines with code that isn't purely structural delimiters. Strips line + block
	comments first; then skips blank lines and lines that contain only braces, parens, commas, and semicolons
	(structural closers of a statement already counted upstream)."""
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
	"""Report logical-SLOC totals per root and combined. Always returns 0 — this is a metric, not a pass/fail audit.
	Useful to refresh the count in REQUIREMENTS.md when the codebase grows."""
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


# ─── selftest ────────────────────────────────────────────────────────────────

def run_selftest():
	"""Regression checks for strip_comments_strings (the shared helper the static-first / method-order /
	final-classes audits depend on) and for the reflow audit's comma-join scanner (flag / no-flag cases pinning the
	CLAUDE.md exclusions, including that text-block interior lines never register as joinable — mirrored by a
	code-reflow case driving refactor.py's transform over a text-block fixture). Each stripper case pins the exact
	stripped output. The multi-line URL case is the load-bearing one: a stripper that removes // comments before
	collapsing string literals mis-pairs the quotes around "http://…" and swallows every line up to the next quote,
	silently blinding those audits over the swallowed region — stripped output must always keep the raw newline
	count."""
	cases = [
		('slash-slash inside string',
			'String url = "http://ns.adobe.com/xap/1.0/";\nint x = 1;',
			'String url = "";\nint x = 1;'),
		('multi-line URL fixture keeps every line',
			'String a = "content://x";\nint between = 1;\nString b = "http://y";\nint after = 2;',
			'String a = "";\nint between = 1;\nString b = "";\nint after = 2;'),
		('escaped quote inside string',
			'String s = "a\\"b // c";\nint y = 2;',
			'String s = "";\nint y = 2;'),
		('escaped backslash before closing quote',
			'String w = "c:\\\\";\nint k = 4;',
			'String w = "";\nint k = 4;'),
		('slash-star inside string',
			'String p = "/* not a comment";\nint z = 3;',
			'String p = "";\nint z = 3;'),
		('real line comment dropped, newline kept',
			'int x = 1; // note\nint y = 2;',
			'int x = 1; \nint y = 2;'),
		('block comment collapses to its newlines',
			'int a = 1; /* one\ntwo\nthree */ int b = 2;',
			'int a = 1; \n\n int b = 2;'),
		('double quote inside char literal',
			'char q = \'"\'; String t = "x";',
			"char q = ''; String t = \"\";"),
		('text block collapses, interior newlines kept',
			'String tb = """\nline // one\n""";\nint m = 5;',
			'String tb = ""\n\n;\nint m = 5;'),
		('unterminated literal closes at the newline',
			'String bad = "oops\nint ok = 6;',
			'String bad = ""\nint ok = 6;'),
	]
	failures = 0
	for name, src, expected in cases:
		got = strip_comments_strings(src)
		line_ok = got.count('\n') == src.count('\n')
		if got != expected or not line_ok:
			failures += 1
			print(f'FAIL {name}')
			print(f'  expected: {expected!r}')
			print(f'  got:      {got!r}')

	# Comma-join scanner cases: (name, lines, expected hit count). Mirrors the CLAUDE.md exclusions — a fitting
	# comma-wrapped call / declaration is flagged; array-literal fixture rows, enum constant lists, case labels,
	# annotations, trailing comments, text-block interior lines, and constructs exceeding 120 cols joined are not.
	comma_cases = [
		('fitting comma-wrapped call flagged',
			['\t\tfoo(bar,', '\t\t\tbaz);'], 1),
		('fitting comma-wrapped declaration flagged',
			['\tprivate static int frob(int a,', '\t\t\tint b)', '\t{'], 1),
		('whole three-line construct flagged once',
			['\t\tfoo(a,', '\t\t\tb,', '\t\t\tc);'], 1),
		('K&R array-literal row not flagged',
			['\tprivate static final int[] P = {', '\t\t1, 2,', '\t\t3,', '\t};'], 0),
		('Allman array-literal row not flagged',
			['\tprivate static final byte[] D =', '\t{', '\t\t(byte) 0xFF, 0x00,', '\t};'], 0),
		('new byte[] initializer row not flagged',
			['\t\tpayload.write(new byte[] {', '\t\t\t0x12, 0x01,', '\t\t});'], 0),
		('2D boolean-mask row not flagged',
			['\t\tboolean[][] m = {', '\t\t\t{ true, false },', '\t\t\t{ false, true },', '\t\t};'], 0),
		('construct exceeding 120 joined not flagged',
			['\t\tfoo(' + 'a' * 100 + ',', '\t\t\t' + 'b' * 40 + ');'], 0),
		('oversize construct not partially flagged',
			['\t\tfoo(a,', '\t\t\tb,', '\t\t\t' + 'c' * 120 + ');'], 0),
		('enum constant list not flagged',
			['public enum Format', '{', '\tJPEG("jpg"),', '\tPNG("png");', '}'], 0),
		('case label not flagged',
			['\t\tcase FOO,', '\t\t\tBAR -> x();'], 0),
		('annotation line not flagged',
			['\t@Foo(a,', '\t\tb)'], 0),
		('trailing comment not flagged',
			['\t\tfoo(bar,  // note', '\t\t\tbaz);'], 0),
		('chain after terminator not flagged',
			['\t\tfoo(a,', '\t\t\tb)', '\t\t\t.bar();'], 0),
		('text-block interior comma line not flagged',
			['\tString sql = """', '\t\tSELECT a,', '\t\tb', '\t\t""";'], 0),
		('text-block interior comma run not flagged',
			['\tString msg = """', '\t\tHello a,', '\t\tHello b,', '\t\tdone', '\t\t""";'], 0),
		('code after a closed text block still flagged',
			['\tString sql = """', '\t\tSELECT a', '\t\t""";', '\t\tfoo(bar,', '\t\t\tbaz);'], 1),
	]
	comma_failures = 0
	for name, lines, expected in comma_cases:
		hits = _scan_comma_joins(lines)
		if len(hits) != expected:
			comma_failures += 1
			print(f'FAIL {name}')
			print(f'  expected {expected} hit(s), got {len(hits)}: {hits!r}')
	failures += comma_failures

	# py-reflow cases: (name, input_text, expected_text) through refactor.py's `py` transform (_reflow_py_text).
	# Pins both reflow directions (join premature wraps, wrap over-120), the never-touch guards (shebang, list
	# markers, `Key: value` enumerations, non-docstring string literals, backslash-carrying docstrings), the
	# provably-safe comma code wrap, the manual-fix report, and idempotency (every case re-runs on its own output).
	sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
	import refactor
	long_comment = '# ' + ' '.join(['word'] * 30)
	wrapped_comment = '# ' + ' '.join(['word'] * 23) + '\n# ' + ' '.join(['word'] * 7)
	wide_call = "foo('" + 'a' * 80 + "', '" + 'b' * 40 + "')"
	wide_call_wrapped = "foo('" + 'a' * 80 + "',\n\t'" + 'b' * 40 + "')"
	py_cases = [
		('py comment premature wrap joins',
			'x = 1\n# alpha beta\n# gamma\ny = 2\n',
			'x = 1\n# alpha beta gamma\ny = 2\n'),
		('py over-120 comment wraps',
			long_comment + '\nx = 1\n',
			wrapped_comment + '\nx = 1\n'),
		('py shebang never reflowed',
			'#!/usr/bin/env python3\n# a b\n# c\nx = 1\n',
			'#!/usr/bin/env python3\n# a b c\nx = 1\n'),
		('py list paragraph untouched',
			'# - one\n# - two\nx = 1\n',
			'# - one\n# - two\nx = 1\n'),
		('py Key: value enumeration untouched',
			'# Panel 1: alpha\n# Panel 2: beta\nx = 1\n',
			'# Panel 1: alpha\n# Panel 2: beta\nx = 1\n'),
		('py non-docstring string untouched',
			"x = '''alpha\nbeta\n'''\ny = 1\n",
			"x = '''alpha\nbeta\n'''\ny = 1\n"),
		('py docstring joins and keeps closing delimiter',
			'def f():\n\t"""Alpha beta\n\tgamma."""\n\treturn 1\n',
			'def f():\n\t"""Alpha beta gamma."""\n\treturn 1\n'),
		('py docstring with backslash untouched',
			'def f():\n\t"""Alpha \\n beta\n\tgamma."""\n\treturn 1\n',
			'def f():\n\t"""Alpha \\n beta\n\tgamma."""\n\treturn 1\n'),
		('py code wraps after outermost comma',
			wide_call + '\n',
			wide_call_wrapped + '\n'),
	]
	py_failures = 0
	for name, src, expected in py_cases:
		got = refactor._reflow_py_text(src, '<selftest>', [])
		got_again = refactor._reflow_py_text(got, '<selftest>', [])
		if got != expected or got_again != got:
			py_failures += 1
			tag = ' (not idempotent)' if got == expected else ''
			print(f'FAIL {name}{tag}')
			print(f'  expected: {expected!r}')
			print(f'  got:      {got!r}')
	unsafe_line = "x = '" + 'a' * 130 + "'\n"
	unsafe_report = []
	unsafe_got = refactor._reflow_py_text(unsafe_line, '<selftest>', unsafe_report)
	if unsafe_got != unsafe_line or len(unsafe_report) != 1 or 'no comma' not in unsafe_report[0][2]:
		py_failures += 1
		print('FAIL py unsafe over-120 line reported for manual fix')
		print(f'  got: {unsafe_got!r} report: {unsafe_report!r}')
	failures += py_failures

	# code-reflow case: refactor.py's whole-file code transform must leave text-block interiors untouched while
	# still joining an ordinary comma construct elsewhere in the same text.
	tb_src = ('\t\tString sql = """\n\t\t\tSELECT a,\n\t\t\tb\n\t\t\t""";\n'
		'\t\tfoo(bar,\n\t\t\tbaz);')
	tb_expected = ('\t\tString sql = """\n\t\t\tSELECT a,\n\t\t\tb\n\t\t\t""";\n'
		'\t\tfoo(bar, baz);')
	tb_got, _ = refactor._reflow_code_text(tb_src)
	if tb_got != tb_expected:
		failures += 1
		print('FAIL code-reflow leaves text-block interior untouched')
		print(f'  expected: {tb_expected!r}')
		print(f'  got:      {tb_got!r}')

	total = len(cases) + len(comma_cases) + len(py_cases) + 2
	if failures == 0:
		print(f'OK: {total} self-test case(s) passed '
			f'({len(cases)} stripper, {len(comma_cases)} comma-join, {len(py_cases) + 1} py-reflow, '
			f'1 code-reflow).')
		return 0
	print(f'TOTAL: {failures} of {total} self-test case(s) failed')
	return 1


# ─── dispatcher ──────────────────────────────────────────────────────────────

AUDITS = {
	'over-cols': audit_over_cols,
	'over-cols-py': audit_over_cols_py,
	'ignored-catches': audit_ignored_catches,
	'static-first': audit_static_first,
	'method-order': audit_method_order,
	'adjacent-comment-styles': audit_adjacent_comment_styles,
	'final-classes': audit_final_classes,
	'reflow': audit_reflow,
	'lsloc': audit_lsloc,
}


def main():
	# Reconfigure stdout/stderr to UTF-8 with replacement on Windows where the default console encoding is cp1252 /
	# cp437. The `reflow` audit prints raw source-line snippets that contain Unicode characters (em-dashes, arrows,
	# the `─` section-divider character). Without this, `python scripts/audit.py` on Windows raises
	# UnicodeEncodeError mid-reflow and never reaches the `lsloc` step — defeating CLAUDE.md's documented
	# "cross-shell self-audit runner" promise. `reconfigure` is Python 3.7+; CropCenter pins ≥ 3.9, so the call is
	# always safe. The try/except handles the edge case where stdout has been rewrapped to a non-text stream
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
	if name == 'selftest':
		# Not in AUDITS: the 9 audits scan the Java tree / scripts; selftest checks the runner's own stripper,
		# comma-join scanner, and refactor.py's py + code reflows against embedded fixtures and takes no roots.
		return run_selftest()
	if name not in AUDITS:
		print(f'unknown audit: {name}', file=sys.stderr)
		print(f'available: {", ".join(AUDITS.keys())}', file=sys.stderr)
		return 2
	return AUDITS[name](args[1:])


if __name__ == '__main__':
	sys.exit(main())
