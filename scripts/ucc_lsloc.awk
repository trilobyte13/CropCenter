# UCC-style LSLOC approximation for Java.
#
# UCC counts, for Java, each of the following as 1 logical SLOC:
#   (a) Executable statements (terminated by `;`)
#   (b) Data declarations (field/var decls — also end in `;`, so caught by (a))
#   (c) Control structures: if/else/else if/for/while/do/try/catch/finally/
#       switch/case/default that do NOT themselves terminate in `;`
#   (d) Type declarations: class / interface / enum / record / @interface
#   (e) Method and constructor signatures
#
# Bare `{` and `}` are scaffolding — not LSLOC.
#
# Notes:
#   - Strips // and /* ... */ comments (block comments may span lines).
#   - Normalizes string/char literals to a placeholder so their contents do not
#     trigger semicolon / keyword matches.
#   - A `return`/`throw`/`break`/`continue` line ends with `;` so it is counted
#     once under (a); we do NOT double-count them as control structures.

BEGIN {
    in_block_comment = 0
}

{
    line = $0

    # Close any open block comment
    while (in_block_comment) {
        if (match(line, /\*\//)) {
            line = substr(line, RSTART + 2)
            in_block_comment = 0
        } else {
            line = ""
            break
        }
    }

    # Strip same-line /* ... */ comments, or open a new block comment
    while (match(line, /\/\*/)) {
        before = substr(line, 1, RSTART - 1)
        after = substr(line, RSTART + 2)
        if (match(after, /\*\//)) {
            line = before substr(after, RSTART + 2)
        } else {
            line = before
            in_block_comment = 1
            break
        }
    }

    # Strip // line comments
    sub(/\/\/.*/, "", line)

    # Normalize string and char literals to empty placeholders so their contents
    # do not affect semicolon / keyword detection.
    gsub(/"[^"]*"/, "\"\"", line)
    gsub(/'[^']*'/, "''", line)

    # (a) + (b): count semicolons as statements/declarations
    nsemi = gsub(/;/, ";", line)
    lsloc += nsemi

    trimmed = line
    sub(/^[ \t]+/, "", trimmed)
    sub(/[ \t]+$/, "", trimmed)

    if (trimmed == "" || trimmed == "{" || trimmed == "}" || trimmed == "});" || trimmed == "};") {
        next
    }

    # (c) Control structures (only if this line didn't already count as a stmt)
    if (nsemi == 0) {
        if (match(trimmed, /^(if|else if|else|for|while|do|try|catch|finally|switch)\b/)) {
            lsloc++
            next
        }
        if (match(trimmed, /^case\b/) || match(trimmed, /^default[[:space:]]*(->|:)/)) {
            lsloc++
            next
        }
    }

    # (d) Type declarations
    if (match(trimmed, /\b(class|interface|enum|record|@interface)\s+[A-Z]/)) {
        lsloc++
        next
    }

    # (e) Method / constructor signature: starts with an access / modifier, has
    # a `(` (parameter list), and doesn't end with `;` (that would be an abstract
    # decl, still counts — so we don't filter on `;`). Anonymous-class and lambda
    # openers also get caught here; that's a small over-count.
    if (match(trimmed, /^(public|protected|private|static|abstract|final|synchronized|native)\s/) && match(trimmed, /\(/)) {
        if (nsemi == 0) {
            lsloc++
        }
    }
}

END {
    print lsloc
}
