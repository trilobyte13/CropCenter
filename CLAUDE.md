# CropCenter Code Style

This document captures the coding conventions for this project. Everything here
is intentional — a compile-clean patch that violates these rules still needs
fixing before merge. New files should match; existing files that drift should
be corrected as they are touched.

## Language level

- **Java 21** (Android `compileSdk 36`, `minSdk 35`).
- Use modern language features where they genuinely clarify intent:
  - **Records** for immutable value types — see "Records" below.
  - **Switch expressions with arrow syntax** (`case X -> { ... }`) for
    multi-way dispatch on discrete values with 3+ cases. No fall-through.
  - **`var`** for local variables whose type is obvious from the right-hand
    side and adds nothing to read.
  - **Pattern matching** for `instanceof` checks where it eliminates a cast.
  - **Diamond operator** (`new ArrayList<>()`) — never re-state type arguments
    when the compiler can infer them.
- Switch vs. if/else: use `switch` for 3+ discrete-value dispatch. Use
  `if`/`else if` for range checks, boolean combinations, or 1–2 cases.
- **`Math.clamp`** for range clamping. Do not hand-roll
  `Math.max(lo, Math.min(hi, x))` — `Math.clamp(x, lo, hi)` (Java 21) is
  clearer and the argument order matches the intent ("value, low, high").

### Records

A class may become a `record` when **every field is effectively immutable** in
practice — no setters, no internal mutation, no external `.field = x;`
assignments. `AspectRatio` and `JpegSegment` qualify; `SelectionPoint` (whose
`active` flag toggles), `GridConfig`, and `ExportConfig` (mutable config bags)
do not.

- Record components become method accessors: `point.x()` not `point.x`. When
  converting an existing class, grep for `.field` access sites and update them
  in the same commit.
- `byte[]` components are fine. Records use `Object.equals` for arrays
  (reference equality), which matches the pre-record behaviour of a plain
  class without `equals` overrides — no behavioural change.
- Instance methods (`isFree()`, `ratio()`) are allowed; records aren't just
  data bags.

## Braces and blocks

- **Full Allman braces.** The opening `{` goes on its own line — for methods,
  classes, interfaces, enums, `if`, `else`, `for`, `while`, `try`, `catch`,
  `finally`, `switch`, lambdas with block bodies, and anonymous-class
  declarations. Example:

  ```java
  public void foo()
  {
      if (condition)
      {
          // ...
      }
      else
      {
          // ...
      }
  }
  ```

- **Always braced.** Even single-statement `if`/`else`/`for`/`while` gets
  braces. No `if (x) return;` one-liners, no `if (x) doThing();` either —
  even when a ladder of conditions looks tempting to format as a table.
- **Lambdas:** parameter list and `->` on the statement line; `{` on its own
  line below, aligned with the statement:

  ```java
  button.setOnClickListener(view ->
  {
      doSomething();
  });
  ```

- **Anonymous classes:** same Allman style. The `new Foo() {` opens with `{`
  on its own line:

  ```java
  spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()
  {
      @Override
      public void onItemSelected(AdapterView<?> parent, View view, int pos, long id)
      {
          // ...
      }

      @Override
      public void onNothingSelected(AdapterView<?> parent)
      {
      }
  });
  ```

- **Empty method bodies** on a single line are the one exception — they look
  sillier with the full Allman expansion:

  ```java
  @Override
  public void onStartTrackingTouch(SeekBar seekBar) {}
  ```

## Annotations

- `@Override` is **mandatory** on any method that overrides or implements one.
- Place annotations on the line immediately above the method signature, not
  inline.
- `@SuppressWarnings` requires a comment explaining why.

## Indentation and wrapping

- **Tabs only**, rendered at width 8.
- **Line length:** wrap at 120 display columns. Count tab as 8.
- **Continuation indent: exactly one tab deeper than the line that starts the
  statement.** This includes:
  - Method call arguments wrapped across lines
  - Method chains (`.foo().bar().baz()` laid out one-per-line)
  - Operator continuations (`&&`, `||`, `+`, `?`, `:`) starting a wrapped line
  - Boolean conditions spanning multiple lines
- **Never double-indent a wrap.** If a wrap sits two tabs deeper than the
  statement, that is a bug. Run the audit scripts at the bottom of this file
  before declaring done.
- **Don't wrap prematurely.** Only wrap when the rendered line (tab width 8)
  exceeds 120 columns. If a call fits under the limit as a one-liner, leave
  it as a one-liner — artificial wraps make diffs noisier and obscure the
  actual structure. When a wrap is forced, prefer refactoring into a cached
  local (`MaterialButton btnFoo = findViewById(...);`) over stretching one
  expression across three lines.
- **Fluent-chain alignment.** When a chain spans multiple lines, every `.foo()`
  sits at the same indent (one tab deeper than the receiver that started the
  chain):

  ```java
  new AlertDialog.Builder(this)
      .setTitle("Save")
      .setView(input)
      .setPositiveButton("OK", (dialog, which) -> save())
      .setNegativeButton("Cancel", null)
      .show();
  ```

  Not `setTitle` at one indent and `setView` at a deeper indent.
- Array initializers use K&R `= { ... };` placement, with elements indented
  one tab from the declaration:

  ```java
  private static final int[] PRESETS = {
      VALUE_A, VALUE_B, VALUE_C,
  };
  ```

## File layout

Top of file:

1. `package` declaration
2. Blank line
3. Imports, grouped: `android.*`, blank, `androidx.*`, blank, `com.*`, blank,
   `java.*`. Alphabetical within each group.
4. Blank line
5. Class-level Javadoc (optional — see Comments)
6. Class declaration

Inside a class:

1. Nested types (interfaces, enums, records) — first, in declaration order
2. Blank line
3. Static fields — see "Field ordering" below
4. Blank line
5. Instance fields
6. Blank line
7. Constructors
8. Methods — see "Method ordering" below

## Field ordering

Within a class, fields are grouped by declaration modifier, in this order:

1. `static final`
2. `static` (non-final)
3. `final` (instance)
4. Regular instance (non-static, non-final)

Within each tier, sort by **type** alphabetically, with **uppercase types
(classes, interfaces) before lowercase primitives** — `String` sorts before
`boolean`, `float`, `int`, `long`. `byte[]` sorts with the lowercase
primitives (it starts with `b`).

Within the same type, sort by **field name** alphabetically.

### One variable per line

Always declare one variable per line. Do not write:

```java
float minX = 0, minY = 0;
```

Write:

```java
float minX = 0;
float minY = 0;
```

## Method ordering

- Constructors first.
- Then methods by access level: `public` → `protected` → package-private →
  `private`.
- Within each access level, sort methods alphabetically by name. Do **not**
  pair getters with their setters — strict alphabetical ordering keeps the
  file scannable with `Ctrl-F` and avoids bikeshedding about what counts as
  a "property". A class with 20 getters followed by 20 setters is fine.
- Within each access level, static methods come **after** instance methods of
  the same access level.
- Android lifecycle overrides (`onCreate`, `onDestroy`, `onNewIntent`, `onDraw`,
  `onTouchEvent`) are ordinary protected methods — they sort alphabetically in
  their access-level section.

### Method references vs lambdas

Prefer a method reference when the lambda body is a single unadapted call:

```java
editorView.setOnZoomChangedListener(this::updateZoomBadge);   // preferred
editorView.setOnZoomChangedListener(() -> this.updateZoomBadge()); // avoid
```

Use a lambda when you need to transform arguments, capture extra state, or
string multiple calls together.

## Imports

- **Never inline a fully-qualified type name.** If you need
  `android.provider.DocumentsContract`, add
  `import android.provider.DocumentsContract;` at the top. The *only*
  exception is a naming collision you can't otherwise resolve.
- No wildcard imports (`import foo.*;`).
- Imports are kept alphabetical within each group.

## Scope minimization

- Fields that could be method-local variables should be. If a value lives
  entirely inside one method, don't lift it to a field.
- Methods that could be `private` should be. `public` only if the class
  contract actually exposes them.
- Classes/interfaces that could be package-private should be.
- A constant used in only one method should be declared `final` inside that
  method, not at class scope.
- A constant used in only one class should be `private static final` on that
  class.
- A constant used across multiple files should live in a shared utility class
  (`ThemeColors`, etc.) — but only then.
- Unused fields and dead code get deleted, not commented out.

## Constants

- Extract magic numbers and strings when they are repeated or when the
  literal itself doesn't tell the reader what it means.
  - `0xFFCBA6F7` appearing in six files → lives in `ThemeColors.MAUVE`.
  - `"Busy — try again"` appearing three times → class-private constant.
  - `8192` buffer size appearing twice → class-private constant.
- Use `SCREAMING_SNAKE_CASE` for constants.
- Add a trailing comment if the value's meaning is non-obvious.

## Comments

- **In-method comments use `//`.** Always. Even multi-line ones.
  Don't use `/* ... */` for a one-liner tucked inside a method or branch —
  `// note` not `/* note */`. `/* ... */` has no place except at the very
  top of a block (method/class Javadoc) when the rules below say Javadoc.
- **Single-line class or method documentation uses `//`**, not one-liner
  Javadoc. `/** Foo. */` becomes `// Foo.`.
- **Multi-line Javadoc uses `/** ... */`** and is only necessary when:
  - The method has `@param` / `@return` / `@throws` tags worth documenting, or
  - The description genuinely needs multiple paragraphs.
  A two-sentence description above a non-tag method is a `//` block, not a
  Javadoc block.
- **No HTML or Javadoc inline tags in comments.** Do not write `<p>`, `<br>`,
  `<cite>`, `<code>`, `{@code ...}`, or `{@link ...}`. This is an Android app
  — Javadoc is not rendered as HTML for end users, and the tags clutter the
  source. Use blank Javadoc lines (`*` on its own line) to separate
  paragraphs. Reference types by their bare name instead of wrapping them in
  `{@link}`.
- **Don't state the obvious.** `// increment counter` before `counter++` is
  noise. Write comments that explain *why*, not *what*.

## Variable names

- **Self-documenting names.** `isLittleEndian` beats `le`. `centerX` beats
  `cx`. `halfWidth` beats `hw`. A short abbreviation "clarified by a comment"
  is worse than just typing the longer name.
- **Avoid one-letter and over-abbreviated names**, except for these standard
  idioms:
  - `i`, `j`, `k` — loop indices
  - `e` — caught exception (`catch (Exception e)`)
  - `n` — count in a read loop (`int n = is.read(buf)`)
  - `x`, `y` — 2D coordinates (`setCenter(float x, float y)`)
  - `r`, `g`, `b` — RGB colour channels
  - `ctx` — `Context` (Android convention)
- Do not use `l` (easily confused with `1`) or `I`/`O` (confused with `0`).
- Android-specific short names that are acceptable because of widespread
  convention: `bmp` (Bitmap), `dp`/`dp4`/`dp8` (density-pixel conversions),
  `tv` (TextView) *inside tiny helper methods*, `lp` (LayoutParams) *inside
  tiny helper methods*, `pp` (Paint) *inside tight drawing loops*. Don't use
  them in long methods where a reader has to remember what they are.

### Collision avoidance

When an outer scope already binds a short name (`left`, `top`, `width`,
`height`…) and a renamed inner variable would shadow it, pick a qualified
form instead: `pixelLeft`, `pixelTop`, `cellWidth`, `thumbHeight`. Do **not**
fall back to one-letter abbreviations (`l`, `t`, `w`, `h`) to resolve the
collision — that reintroduces exactly the opacity this rule exists to prevent.

### Lambda and listener parameters

Android listener callbacks have real parameter names — use them. Do not
abbreviate. Concrete conventions used in this codebase:

| Interface                         | Parameters                                            |
| --------------------------------- | ----------------------------------------------------- |
| `View.OnClickListener`            | `view` (or descriptive like `button`)                 |
| `DialogInterface.OnClickListener` | `(dialog, which)`                                     |
| `DialogInterface.OnCancelListener`| `dialog`                                              |
| `CompoundButton.OnCheckedChange`  | `(button, isChecked)`                                 |
| `SeekBar.OnSeekBarChangeListener` | `(seekBar, progress, fromUser)`                       |
| `TextWatcher.beforeTextChanged`   | `(text, start, count, after)`                         |
| `TextWatcher.onTextChanged`       | `(text, start, before, count)`                        |
| `TextWatcher.afterTextChanged`    | `editable`                                            |
| `OnApplyWindowInsetsListener`     | `(view, insets)` (use `view`, not `v`)                |

Not `(d, w)`, `(b, c)`, `(sb, p, fu)`, `(s, a, b, c)`. The Android source
uses the long names; match them.

## Theme colors

The Catppuccin Mocha palette is defined in two places:

- **`app/src/main/res/values/colors.xml`** — canonical source. Use
  `getResources().getColor(R.color.mauve, null)` whenever a `Context` is
  available.
- **`com.cropcenter.util.ThemeColors`** — parallel `int` constants for code
  paths that don't have a `Context` handy (static helpers, `Paint` setup,
  `Bitmap` color fills, etc.). These mirror the XML values.

Do not inline a hex literal that corresponds to a theme color. If the color
you want doesn't exist in `ThemeColors`, add it there rather than copying the
hex.

## Android / Java idioms

- **Logging TAG:** each class that logs declares `private static final String
  TAG = "ClassName";` at the top of the static-field section. Do not re-
  derive it from `getClass().getSimpleName()`.
- **String formatting:** pass `Locale.ROOT` to `String.format` when the output
  is for internal use (log messages, regex, parsing). Use the system locale
  only for user-facing display.
- **String equality:** use `.equals()` or `.equalsIgnoreCase()`, never `==`.
- **try-with-resources** for anything `Closeable` / `AutoCloseable`. Don't
  hand-roll close-in-finally.
- **Intentionally empty catch** uses the parameter name `ignored`:
  ```java
  catch (NumberFormatException ignored)
  {
  }
  ```
  If the `Exception` deserves a log line, log it instead of naming it
  `ignored`.
- **Toast-from-background-thread helpers** go through a UI-thread-safe path
  (`runOnUiThread` + `isDestroyed()` guard). The `toastIfAlive` helper in
  `MainActivity` is the canonical pattern.
- **`final` on local variables** is required only when a lambda or anonymous
  class captures them. Don't sprinkle `final` on every local for stylistic
  reasons.

## Build & verify

Android build (primary gate):

```bash
./gradlew.bat compileDebugJavaWithJavac
```

Must succeed with no errors. The trailing deprecation warning from
`MainActivity.java` is a known Gradle-9 noise and not a regression signal.

## Self-audit

Before declaring a change done, these checks should come back empty:

```bash
# Double-indent continuations (any line that starts with an operator and is
# more than one tab deeper than the previous non-blank line):
awk '
  { indent=0; t=$0
    while (substr(t,1,1)=="\t") { indent++; t=substr(t,2) }
    if (t !~ /^[[:graph:]]/) next
    if (prev>=0 && t~/^(\.|&&|\|\||\+|\?|:)/ && indent-prev>1)
      print FILENAME":"NR" indent_diff="(indent-prev)
    prev=indent
  }
' $(find app/src/main/java -name '*.java')

# Mismatched chain continuation (consecutive `.foo()` or operator lines
# at different indents):
awk '
  { indent=0; t=$0
    while (substr(t,1,1)=="\t") { indent++; t=substr(t,2) }
    if (t !~ /^[[:graph:]]/) next
    op = (t~/^(\.|\+|&&|\|\|)/)
    if (prev_op && op && indent != prev_indent)
      print FILENAME":"NR" mismatched ("prev_indent"->"indent")"
    prev_indent=indent; prev_op=op
  }
' $(find app/src/main/java -name '*.java')

# Inline fully-qualified names for types:
grep -rnE 'new (android|androidx|java|javax|com\.cropcenter)\.\w+\.\w+' app/src/main/java
grep -rnE '(android|androidx|java|javax|com\.cropcenter)\.\w+\.\w+\s+\w+\s*[=;,)]' app/src/main/java

# HTML / Javadoc inline tags in comments:
grep -rnE '<(p|br|code|cite|i|b|em|strong|ul|ol|li|pre)>' app/src/main/java
grep -rnE '\{@(code|link)' app/src/main/java

# Lingering one-letter variable decls that aren't standard idioms:
grep -rnE '\b(int|float|double|long|boolean|byte\[\]|String|[A-Z]\w+)\s+[a-z]\s*[=;,)]' app/src/main/java \
    | grep -vE 'catch|for \(|\bi\b|\bj\b|\bk\b|\bn\b|\be\b|\bx\b|\by\b|\br\b|\bg\b|\bb\b|\bctx\b'

# Cryptic two-letter lambda parameter tuples:
grep -rnE '\((d, w|b, c|sb, p|s, a|v, i)\)\s*->' app/src/main/java

# Inlined conditionals (violates always-braced rule):
grep -rnE '^\s*(if|else if|for|while)\s*\([^)]*\)\s+[a-zA-Z_]' app/src/main/java \
    | grep -vE '\s+\{\s*$|\s+\{\s*//'

# Hand-rolled Math.max(lo, Math.min(hi, x)) — use Math.clamp instead:
grep -rnE 'Math\.max\([^,]+,\s*Math\.min\(' app/src/main/java

# Single-line /** ... */ Javadoc (use // instead):
grep -rnE '^\s*/\*\*[^*]*\*/\s*$' app/src/main/java

# In-method /* ... */ inline block comments (use // instead):
grep -rnE '/\*[^*\n/][^\n]*\*/' app/src/main/java
```

Build must also be clean:

```bash
./gradlew.bat compileDebugJavaWithJavac
```
