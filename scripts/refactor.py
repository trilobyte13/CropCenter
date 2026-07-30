#!/usr/bin/env python3
"""Consolidated source-refactoring CLI for CropCenter.

Single entry point for every transform: each transform lives as a function inside this file; shared helpers (Java
file walk, EOL-preserving read/write, display-width) live at the top.

Usage:
    python scripts/refactor.py <name> [args...]

Transforms:
    code               join multi-line Java statements (operator and comma
                       continuations) that fit under 120 cols
    comments           reflow Javadoc + // comment paragraphs to 120 cols
    md <file> [width]  reflow markdown paragraphs to width (default 120)
    py                 reflow scripts/*.py docstring + # comment paragraphs to
                       120 cols, wrap over-120 code lines where provably safe
    strip-blanks       remove blank lines around braces, collapse double-blanks

Examples:
    python scripts/refactor.py code               # walks app/src/main+test
    python scripts/refactor.py code --check       # dry-run, print would-change
    python scripts/refactor.py code Foo.java Bar.java
    python scripts/refactor.py comments           # walks app/src tree
    python scripts/refactor.py md REQUIREMENTS.md 120
    python scripts/refactor.py py                 # walks scripts/*.py
    python scripts/refactor.py py --check         # dry-run + manual-fix report
    python scripts/refactor.py strip-blanks       # walks app/src

`code`, `comments`, and `py` accept `--check` for dry-run before the path arguments.
"""
import ast
import glob
import io
import os
import re
import sys
import tokenize


MAX_COLS = 120
TAB_WIDTH = 8


# ─── shared helpers ──────────────────────────────────────────────────────────

def walk_java_files(roots):
	"""Yield (path) for every .java file under each root. Used when no explicit file list is provided."""
	for root in roots:
		for dp, _, fns in os.walk(root):
			for fn in fns:
				if fn.endswith('.java'):
					yield os.path.join(dp, fn)


def display_width(s):
	"""Compute display column count, counting tab as TAB_WIDTH (or to next multiple of TAB_WIDTH). Shared across
	reflow_code and reflow_comments — both need to decide whether a candidate join would fit under MAX_COLS."""
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
	"""Return a flat list of .java paths. If args is empty, walk the default roots. If args contains directories,
	walk them. Otherwise treat each as a file path. Used by code/comments/strip-blanks which all accept either
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

# NOTE: ',' is deliberately absent from the trailing-op class — comma continuations are handled by the whole-construct
# join below (with the CLAUDE.md initializer / enum / case / annotation exclusions), never by the pairwise operator
# join.
_TRAIL_OP = re.compile(r'(?:[+(\[^]|(?<![|])\|(?![|])|(?<![&])&(?![&])|&&|\|\|)\s*$')
_LEAD_OP = re.compile(r'^\s*(?:[+^]|(?<![|])\|(?![|])|(?<![&])&(?![&])|&&|\|\|)\s')
_LEAD_CLOSER = re.compile(r'^\s*\)(?![\s]*\{)')


def _is_comment_line(line):
	s = line.lstrip()
	return s.startswith('//') or s.startswith('*') or s.startswith('/*')


def _in_string_literal_at_eol(line):
	"""Best-effort odd-quote count outside of `//` line comments. Joining is disallowed when the candidate line ends
	inside a string literal."""
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
	"""True when line_a and line_b can be safely joined into a single line under MAX_COLS. Skips method chains
	(`.foo()`), ternary continuations (`? :`), Allman braces, and lines inside string literals. Joins on
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
	"""Combine two lines into one, preserving line_a's leading whitespace and dropping line_b's. Empty separator
	when sa opens with `(`/`[` or sb begins with `)`; single space otherwise."""
	sa = line_a.rstrip()
	sb = line_b.lstrip()
	sep = '' if (sa.endswith(('(', '[')) or _LEAD_CLOSER.match(line_b)) else ' '
	return sa + sep + sb


# ── comma-continuation join ──
#
# A code line ending with ',' starts a comma construct; continuation lines are consumed until
# one ends without ','. The whole construct joins only when the joined line fits MAX_COLS —
# never a partial pairwise join, which would just re-wrap a deliberate layout differently.
# Exclusions, each matching a CLAUDE.md rule:
#   (a) rows inside brace-opened array / collection initializers — `= {`, Allman `=` + `{`,
#       and `new type[] {` forms, tracked by brace context. K&R multi-line initializers and
#       fixture data grids (JPEG byte rows, 2D boolean masks in tests) are deliberate
#       semantic layouts.
#   (b) comment / Javadoc lines, and lines carrying a trailing comment.
#   (c) annotation lines.
#   (d) `case` (and `default`) labels.
#   (e) enum constant lists, tracked by brace context.
#   (f) all pairwise-join skips stay: chains, ternary, string-literal spans, Allman braces
#       (a construct line containing `{` / `}` aborts, and a chain / ternary continuation
#       after the terminator aborts).
#   (g) Java text-block interiors, tracked by a per-line `"""` state scan — the lines between
#       the delimiters are string content, and joining them would rewrite the literal's
#       runtime value.

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
		if not in_block and _is_comment_line(line):
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
	end_idx is the construct's terminator line and `joined` is the single-line form when the construct may join,
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
				or _in_string_literal_at_eol(line) or text_block_flags[j]):
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
		# The statement continues past the terminator in a skipped form (chain / ternary); leave the whole
		# construct alone, matching the pairwise-join skips.
		return j, None
	joined = parts[0].rstrip()
	for part in parts[1:]:
		piece = part.strip()
		sep = '' if piece.startswith(')') else ' '
		joined = joined + sep + piece
	if display_width(joined) > MAX_COLS:
		return j, None
	return j, joined


def _scan_comma_joins(lines):
	"""Return [(start_idx, end_idx, joined)] for every comma construct that can join under MAX_COLS. Tracks brace
	context so rows inside array / collection initializers and enum bodies are never candidates, and text-block
	state so a text block's interior lines are never candidates (nor brace context — their braces are string data);
	a rejected construct's interior comma lines are never re-tried as fresh construct starts (that would produce
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


def _apply_comma_joins(lines):
	"""Apply every joinable comma construct. Returns (new_lines, join_count)."""
	hits = _scan_comma_joins(lines)
	if not hits:
		return lines, 0
	hit_map = {start: (end, joined) for start, end, joined in hits}
	out = []
	i = 0
	n = len(lines)
	while i < n:
		if i in hit_map:
			end, joined = hit_map[i]
			out.append(joined)
			i = end + 1
		else:
			out.append(lines[i])
			i += 1
	return out, len(hits)


def _reflow_code_text(text):
	"""Iterate join passes (comma constructs, then pairwise operator joins) until no more joins fire. Returns (text,
	passes)."""
	lines = text.split('\n')
	pass_num = 0
	while True:
		pass_num += 1
		lines, comma_joined = _apply_comma_joins(lines)
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
		lines = out
		if joined_count == 0 and comma_joined == 0:
			break
	return '\n'.join(lines), pass_num - 1


def refactor_code(args):
	"""Conservative code reflow — joins multi-line Java statements that fit under 120 cols. Pairwise joins fire on
	trailing `+` `&&` `||` `(`, on leading-operator continuation, and on leading-closer continuation. Comma
	continuations join only as a whole construct (see the comma-continuation section above) with the CLAUDE.md
	exclusions: array / collection initializer rows, enum constant lists, text-block interiors, comment / annotation
	/ `case` lines. Shared explicit skips: method chains, ternary, Allman braces, string literals. `--check` does a
	dry-run that prints would-change files."""
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


def _paragraph_structure_skip(paragraph):
	"""True when the paragraph carries structure that must not be reflowed:
	tags, list markers, intro-colon lines, section dividers, mid-paragraph
	single-word lines, and paragraphs whose lines have visibly different
	indentation (code samples, tables). Shared by the Java comment reflow
	(via _should_skip_paragraph) and the py transform, which adds its own
	length / over-120 rule on top."""
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


def _should_skip_paragraph(paragraph):
	"""True when the paragraph should be left alone (not reflowed). Single lines are always left alone; multi-line
	paragraphs defer to the shared structure rules."""
	if len(paragraph) < 2:
		return True
	return _paragraph_structure_skip(paragraph)


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
	"""Join paragraph lines into one. Soft-wrap hyphenation (line ends with `-` and next line starts lowercase)
	joins with no space."""
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
	"""Reflow the inside of a `/** ... */` block. `text` is the raw content between (and not including) the `/**`
	and `*/` delimiters."""
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
	"""Reflow Javadoc + `//` comment paragraphs to 120 cols. Skips tag lines (`@param` etc), list markers,
	intro-colon lines, section dividers, and paragraphs with uneven indentation. `--check` does a dry-run."""
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
	"""Wrap a logical line to `width`, breaking at spaces. Words longer than the budget stay on their own line
	(don't break inside URLs / identifiers / inline code)."""
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
				or _DEFINITION_RE.match(stripped)
				or line.startswith('>')):
			# Definition lines (`**Key**: value`) are emitted verbatim, matching the docstring contract —
			# REQUIREMENTS.md's LSLOC pin line is one and its numbers must never rewrap. Continuation lines
			# below a definition line reflow as their own paragraph.
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
	"""Reflow markdown paragraphs to a target column width (default 120). Skips fenced code blocks, tables,
	headings, horizontal rules, and definition lines. Preserves the file's existing EOL convention (CRLF vs LF)."""
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


# ─── transform: py ───────────────────────────────────────────────────────────
#
# Reflows the Python tooling itself (scripts/*.py) with the same 120-display-col
# discipline as the Java tree. Three phases, each provably scoped via ast /
# tokenize so string literals that aren't docstrings, shebang / coding lines,
# and code are never rewritten by the prose phases:
#   1. docstring paragraphs — join premature wraps, wrap over-120 (ast-located,
#      so only true docstrings; literals with backslashes are left alone)
#   2. full-line # comment runs — same join + wrap
#   3. over-120 CODE lines — wrap ONLY where provably safe: after a comma at
#      the outermost available bracket level, continuation one indent unit
#      deeper in the file's own style (tab vs 4-space). Anything not provably
#      safe (no such comma, trailing comment, multi-line string, backslash
#      continuation, structural comment paragraph) goes to a printed report
#      for manual fix.

def _is_py_header_comment(line_no, line):
	"""True for the shebang (line 1) and PEP 263 coding declarations (lines 1-2) — never reflowed."""
	if line_no == 1 and line.startswith('#!'):
		return True
	if line_no <= 2 and re.match(r'^#.*coding[:=]', line):
		return True
	return False


def _py_indent_unit(text):
	"""Return the file's continuation indent unit: one tab when the file indents with tabs, four spaces otherwise
	(both continuation styles are already in use across scripts/)."""
	tab_lines = len(re.findall(r'^\t', text, re.M))
	space_lines = len(re.findall(r'^ ', text, re.M))
	return '\t' if tab_lines >= space_lines else '    '


# `Key: value` enumeration line — a short label (≤ 30 chars, no interior colon) followed by
# `: ` and a payload. Two or more such lines in one paragraph mean a definition table (Panel
# lists, formula tables, case enumerations), not prose wrapped mid-sentence.
_PY_KV_LINE = re.compile(r'^[^\s:][^:]{0,29}:\s')


def _py_para_skip(texts, over):
	"""True when a py comment / docstring paragraph must be left alone:
	structural content per the shared rules, a `Key: value` enumeration
	(2+ such lines), or a single line that already fits (`over` is True when
	any of the paragraph's raw lines exceeds 120 display cols — the wrap
	direction applies even to one-line paragraphs)."""
	if _paragraph_structure_skip(texts):
		return True
	if sum(1 for t in texts if _PY_KV_LINE.match(t.strip())) >= 2:
		return True
	if len(texts) < 2 and not over:
		return True
	return False


def _wrap_two_prefix(text, first_prefix, cont_prefix, max_cols):
	"""Greedy word wrap where the first output line carries first_prefix and continuation lines carry cont_prefix.
	Words longer than the budget stay on their own line (URLs / identifiers are never broken mid-word)."""
	words = text.split()
	if not words:
		return [first_prefix]
	lines = []
	cur = first_prefix + words[0]
	for word in words[1:]:
		cand = cur + ' ' + word
		if display_width(cand) > max_cols and display_width(cur) > display_width(cont_prefix):
			lines.append(cur)
			cur = cont_prefix + word
		else:
			cur = cand
	lines.append(cur)
	return lines


def _reflow_py_comment_run(run, indent):
	"""Reflow one contiguous run of full-line `#` comments sharing `indent`. Bare `#` lines and section dividers
	split paragraphs and pass through verbatim; structural paragraphs (lists, colon intros, uneven indent) pass
	through untouched."""
	out = []
	para_texts = []
	para_raw = []

	def flush():
		if not para_texts:
			return
		over = any(display_width(raw) > MAX_COLS for raw in para_raw)
		if _py_para_skip(para_texts, over):
			out.extend(para_raw)
		else:
			joined = _join_paragraph_lines(para_texts)
			out.extend(_wrap_to_width(joined, indent + '# ', MAX_COLS))
		para_texts.clear()
		para_raw.clear()

	for raw in run:
		m = re.match(r'^\s*#(\s?)(.*)$', raw)
		content = m.group(2)
		if not content.strip() or _is_section_divider(content):
			flush()
			out.append(raw)
		else:
			para_texts.append(content)
			para_raw.append(raw)
	flush()
	return out


def _reflow_py_comments(text):
	"""Find runs of full-line `#` comments (tokenize-located, so a `#` inside a string never counts) and reflow each
	run's paragraphs to 120 cols."""
	try:
		toks = list(tokenize.generate_tokens(io.StringIO(text).readline))
	except (tokenize.TokenError, IndentationError, SyntaxError):
		return text
	full = {}
	for tok in toks:
		if tok.type == tokenize.COMMENT and tok.line[:tok.start[1]].strip() == '':
			full[tok.start[0]] = tok.line[:tok.start[1]]
	lines = text.split('\n')
	out = []
	i = 0
	n = len(lines)
	while i < n:
		line_no = i + 1
		if line_no in full and not _is_py_header_comment(line_no, lines[i]):
			indent = full[line_no]
			run = []
			j = i
			while (j < n and (j + 1) in full and full[j + 1] == indent
					and not _is_py_header_comment(j + 1, lines[j])):
				run.append(lines[j])
				j += 1
			out.extend(_reflow_py_comment_run(run, indent))
			i = j
			continue
		out.append(lines[i])
		i += 1
	return '\n'.join(out)


def _reflow_py_docstring_block(block):
	"""Reflow the prose paragraphs of one multi-line docstring (`block` is its raw lines, opening delimiter first).
	Returns the replacement lines, or None when the docstring must be left alone: prefixed literals (r/b/f), any
	backslash (escape or continuation semantics would change), or an interior delimiter."""
	m = re.match(r'^(\s*)("""|\'\'\')(.*)$', block[0])
	if not m:
		return None
	indent = m.group(1)
	delim = m.group(2)
	first_text = m.group(3)
	if any('\\' in line for line in block):
		return None
	closing_raw = block[-1]
	if not closing_raw.rstrip().endswith(delim):
		return None
	closing_body = closing_raw.rstrip()[:-len(delim)]
	middle = block[1:-1]
	if delim in first_text or delim in closing_body or any(delim in line for line in middle):
		return None
	closing_own_line = closing_body.strip() == ''
	# Ordered content entries: (kind, text, raw_width). 'open' is the text after the opening delimiter (no indent of
	# its own); 'mid' entries keep their raw form.
	entries = []
	if first_text.strip():
		entries.append(('open', first_text, display_width(block[0])))
	for line in middle:
		entries.append(('mid', line, display_width(line)))
	if not closing_own_line:
		entries.append(('mid', closing_body, display_width(closing_raw)))
	# Split into paragraphs on blank lines.
	paras = []
	cur = []
	for entry in entries:
		if entry[1].strip() == '':
			if cur:
				paras.append(cur)
				cur = []
			paras.append([entry])
		else:
			cur.append(entry)
	if cur:
		paras.append(cur)
	out = []
	if not first_text.strip():
		out.append(block[0].rstrip())
	for para in paras:
		if para[0][1].strip() == '':
			out.append('')
			continue
		texts = [text if kind == 'open' else text.strip() for kind, text, _ in para]
		mids = [text for kind, text, _ in para if kind == 'mid']
		mid_indents = {t[:len(t) - len(t.lstrip(' \t'))] for t in mids}
		over = any(width > MAX_COLS for _, _, width in para)
		if _py_para_skip(texts, over) or len(mid_indents) > 1:
			for kind, text, _ in para:
				out.append(block[0].rstrip() if kind == 'open' else text.rstrip())
			continue
		para_indent = next(iter(mid_indents)) if mid_indents else indent
		joined = _join_paragraph_lines(texts)
		if para[0][0] == 'open':
			out.extend(_wrap_two_prefix(joined, indent + delim, para_indent, MAX_COLS))
		else:
			out.extend(_wrap_to_width(joined, para_indent, MAX_COLS))
	if closing_own_line:
		out.append(closing_raw.rstrip())
	elif display_width(out[-1] + delim) <= MAX_COLS:
		out[-1] = out[-1] + delim
	else:
		out.append(indent + delim)
	return out


def _reflow_py_docstrings(text):
	"""Locate every module / class / function docstring via ast (so string literals that aren't docstrings are never
	touched) and reflow each multi-line one. Spans are patched bottom-up so line numbers stay valid."""
	try:
		tree = ast.parse(text)
	except SyntaxError:
		return text
	spans = []
	for node in ast.walk(tree):
		if not isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
			continue
		body = node.body
		if (body and isinstance(body[0], ast.Expr) and isinstance(body[0].value, ast.Constant)
				and isinstance(body[0].value.value, str)):
			spans.append((body[0].value.lineno, body[0].value.end_lineno))
	lines = text.split('\n')
	for start, end in sorted(spans, reverse=True):
		if end <= start:
			continue
		new_block = _reflow_py_docstring_block(lines[start - 1:end])
		if new_block is not None:
			lines[start - 1:end] = new_block
	return '\n'.join(lines)


def _split_py_code_line(line, cols, unit):
	"""Split `line` after the given comma columns (all syntactically inside an open bracket) so every resulting
	piece fits 120 display cols, greedily taking the last fitting comma each round. Continuations sit one `unit`
	deeper than the line's own indent. Returns the piece list, or None when no split gets every piece under the
	limit."""
	leading = line[:len(line) - len(line.lstrip(' \t'))]
	cont_prefix = leading + unit
	pieces = []
	cur = line
	cur_cols = sorted(cols)
	while display_width(cur) > MAX_COLS:
		fits = [c for c in cur_cols if display_width(cur[:c + 1]) <= MAX_COLS and cur[c + 1:].strip()]
		if not fits:
			return None
		brk = max(fits)
		pieces.append(cur[:brk + 1])
		rest = cur[brk + 1:]
		stripped = rest.lstrip()
		dropped = len(rest) - len(stripped)
		cur_cols = [len(cont_prefix) + (c - brk - 1 - dropped) for c in cur_cols if c > brk + dropped]
		cur = cont_prefix + stripped
	pieces.append(cur)
	return pieces


def _wrap_py_code(text, path, report):
	"""Wrap every over-120 code line that is provably safe to wrap (comma inside an open bracket, no trailing
	comment, not in a multi-line string, no backslash continuation). Every over-120 line that can't be wrapped is
	appended to `report` as (path, line_no, reason) for manual fixing."""
	lines = text.split('\n')
	if not any(display_width(line) > MAX_COLS for line in lines):
		return text
	try:
		toks = list(tokenize.generate_tokens(io.StringIO(text).readline))
	except (tokenize.TokenError, IndentationError, SyntaxError):
		for i, line in enumerate(lines):
			if display_width(line) > MAX_COLS:
				report.append((path, i + 1, 'file does not tokenize'))
		return text
	comment_full = set()
	comment_trailing = set()
	multi_string = set()
	commas = {}
	depth = 0
	fstring_depth = 0
	fstring_start = getattr(tokenize, 'FSTRING_START', None)
	fstring_end = getattr(tokenize, 'FSTRING_END', None)
	for tok in toks:
		if fstring_start is not None and tok.type == fstring_start:
			fstring_depth += 1
		elif fstring_end is not None and tok.type == fstring_end:
			fstring_depth -= 1
		elif tok.type == tokenize.OP and fstring_depth == 0:
			if tok.string in '([{':
				depth += 1
			elif tok.string in ')]}':
				depth -= 1
			elif tok.string == ',' and depth >= 1:
				commas.setdefault(tok.start[0], []).append((tok.start[1], depth))
		elif tok.type == tokenize.COMMENT:
			if tok.line[:tok.start[1]].strip() == '':
				comment_full.add(tok.start[0])
			else:
				comment_trailing.add(tok.start[0])
		elif tok.type == tokenize.STRING and tok.end[0] > tok.start[0]:
			multi_string.update(range(tok.start[0], tok.end[0] + 1))
	unit = _py_indent_unit(text)
	out = []
	for i, line in enumerate(lines):
		line_no = i + 1
		if display_width(line) <= MAX_COLS:
			out.append(line)
			continue
		if line_no in comment_full:
			report.append((path, line_no, 'structural comment paragraph still over 120'))
			out.append(line)
			continue
		if line_no in multi_string:
			report.append((path, line_no, 'inside a multi-line string'))
			out.append(line)
			continue
		if line_no in comment_trailing:
			report.append((path, line_no, 'trailing comment'))
			out.append(line)
			continue
		if line.rstrip().endswith('\\'):
			report.append((path, line_no, 'backslash continuation'))
			out.append(line)
			continue
		cands = [(c, d) for c, d in commas.get(line_no, []) if line[c + 1:].strip()]
		if not cands:
			report.append((path, line_no, 'no comma inside an open bracket'))
			out.append(line)
			continue
		pieces = None
		for level in sorted({d for _, d in cands}):
			cols = [c for c, d in cands if d <= level]
			pieces = _split_py_code_line(line, cols, unit)
			if pieces is not None:
				break
		if pieces is None:
			report.append((path, line_no, 'no comma split keeps every piece under 120'))
			out.append(line)
			continue
		out.extend(pieces)
	return '\n'.join(out)


def _reflow_py_text(text, path, report):
	"""Full py transform: docstring paragraphs, then `#` comment runs, then provably-safe code wraps. Appends (path,
	line_no, reason) to `report` for every over-120 line the code phase can't safely fix; line numbers refer to the
	returned text."""
	text = _reflow_py_docstrings(text)
	text = _reflow_py_comments(text)
	return _wrap_py_code(text, path, report)


def refactor_py(args):
	"""Reflow the Python tooling in scripts/ to the 120-display-col limit:
	docstring + `#` comment paragraphs both directions (join premature
	wraps, wrap over-long lines; structural paragraphs and non-docstring
	string literals never touched), plus provably-safe code wraps. Prints a
	MANUAL FIX report for over-120 lines it can't wrap safely. `--check`
	dry-runs. Exit code 0 only when nothing would change AND the report is
	empty — usable as a drift gate."""
	dry_run = False
	if args and args[0] == '--check':
		dry_run = True
		args = args[1:]
	files = args or sorted(glob.glob(os.path.join('scripts', '*.py')))
	report = []
	total = changed = 0
	for path in files:
		text, eol = read_preserving_eol(path)
		new_text = _reflow_py_text(text, path, report)
		total += 1
		if new_text == text:
			continue
		changed += 1
		before = text.count('\n')
		after = new_text.count('\n')
		tag = '[CHECK]' if dry_run else '[REFLOW]'
		print(f'  {tag} {path}: {before} -> {after} lines')
		if not dry_run:
			write_preserving_eol(path, new_text, eol)
	verb = 'would change' if dry_run else 'changed'
	print(f'\n{changed}/{total} files {verb}')
	if report:
		print('\nMANUAL FIX REQUIRED (not provably safe to wrap):')
		for rpath, line_no, reason in report:
			print(f'  {rpath}:{line_no} {reason}')
	if report or (dry_run and changed):
		return 1
	return 0


# ─── transform: strip-blanks ─────────────────────────────────────────────────

def _strip_blanks_text(text):
	"""Three passes: remove blanks before `}` lines, remove blanks after `{` lines, collapse runs of 2+ blank lines
	to one. Returns (new_text, eol)."""
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
	"""Strip three superfluous-blank patterns from Java sources: blanks before `}` lines, blanks after `{` lines,
	runs of 2+ blanks. Idempotent."""
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
	'py': refactor_py,
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
