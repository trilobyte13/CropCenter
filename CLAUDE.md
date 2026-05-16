# CropCenter Code Style

This document captures the coding conventions for this project. Everything here is intentional — a compile-clean patch
that violates these rules still needs fixing before merge. New files should match; existing files that drift should be
corrected as they are touched.

Sections are ordered alphabetically. Subsections inside each section are also alphabetical.

## Android / Java idioms

- **Logging TAG:** each class that logs declares `private static final String TAG = "ClassName";` at the top of the
  static-field section. Do not re-derive it from `getClass().getSimpleName()`.
- **String formatting:** pass `Locale.ROOT` to `String.format` when the output is for internal use (log messages, regex,
  parsing). Use the system locale only for user-facing display.
- **String equality:** use `.equals()` or `.equalsIgnoreCase()`, never `==`.
- **try-with-resources** for anything `Closeable` / `AutoCloseable`. Don't hand-roll close-in-finally.
- **Intentionally swallowed exceptions** use the parameter name `ignored`, and the catch body must contain a `//`
  comment explaining *why* the throw is safe to drop. The reader needs to see at a glance which throws are expected
  by design (user mid-keystroke input not yet parseable, EXIF tag missing on a non-camera image, optional MediaStore
  column absent on this OEM) versus which were silently swallowed bugs. The rule applies whether the catch body is
  empty or carries fallback logic (`return defaultValue`):
  ```java
  catch (NumberFormatException ignored)
  {
      // EditText fires the watcher on every keystroke; partial input ("12.", "-") is expected.
  }

  catch (NumberFormatException ignored)
  {
      // Caller passed the default; bad numeric strings are routine for user-typed fields.
      return def;
  }
  ```
  If the `Exception` actually deserves a log line, log it (`Log.w(TAG, "...", e)`) instead of naming it `ignored`.
- **Toast-from-background-thread helpers** go through a UI-thread-safe path (`runOnUiThread` + `isDestroyed()` guard).
  The `toastIfAlive` helper in `MainActivity` is the canonical pattern.
- **`final` on local variables** is required only when a lambda or anonymous class captures them. Don't sprinkle `final`
  on every local for stylistic reasons.

## Annotations

- `@Override` is **mandatory** on any method that overrides or implements one.
- Place annotations on the line immediately above the method signature, not inline.
- `@SuppressWarnings` requires a comment explaining why.

## Braces and blocks

- **Full Allman braces.** The opening `{` goes on its own line — for methods, classes, interfaces, enums, `if`, `else`,
  `for`, `while`, `try`, `catch`, `finally`, `switch`, lambdas with block bodies, and anonymous-class declarations.
  Example:

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

- **Always braced.** Even single-statement `if`/`else`/`for`/`while` gets braces. No `if (x) return;` one-liners, no `if
  (x) doThing();` either — even when a ladder of conditions looks tempting to format as a table.
- **Lambdas:** parameter list and `->` on the statement line; `{` on its own line below, aligned with the statement:

  ```java
  button.setOnClickListener(view ->
  {
      doSomething();
  });
  ```

- **Anonymous classes:** same Allman style. The `new Foo() {` opens with `{` on its own line:

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

- **Empty method bodies** on a single line are the one exception — they look sillier with the full Allman expansion:

  ```java
  @Override
  public void onStartTrackingTouch(SeekBar seekBar) {}
  ```

## Build & verify

Android build (primary gate):

```bash
./gradlew.bat compileDebugJavaWithJavac
```

Must succeed with no errors. The trailing deprecation warning from `MainActivity.java` is a known Gradle-9 noise and not
a regression signal.

## Comments

- **Javadoc is required on:**
  - Type declarations (class, interface, enum, record)
  - Public and protected methods (the external API surface — IDE tooltips and subclass contracts depend on them)
  - Non-public methods (package-private, private) with non-trivial logic — branching, side effects, error handling,
    ordering guarantees, memory-visibility concerns, or any invariant a reader would miss by reading the body

- **Javadoc is optional — and should be omitted when it would only restate the signature — on:**
  - Record component accessors (the record-level Javadoc already names the components)
  - Trivial getters / setters where the method name matches a field and there is no transformation
  - `@Override` methods whose contract matches the supertype's documented contract (the IDE surfaces the supertype
    Javadoc)
  - Private helpers under ~5 lines with self-documenting names: `isEmpty()`, `reset()`, `toPx(int dp)`
  - `withXxx(value)` record transformers whose behavior is exhaustively described by the name (`withRows`, `withColor`)
    — a *non-obvious* transformer (`withFormat` accepting strings outside the FORMAT_* set, `withBounds` that also
    recomputes derived state) still gets Javadoc

When in doubt, write the Javadoc. But `/** Returns the width. */` on `int width()` is worse than nothing — it trains
readers to skim every Javadoc block and lose the ones that actually say something.

- **When Javadoc IS present, it's a multi-line block.** Inline `/** Foo. */` on one physical line stays out; expand to:

  ```java
  /**
   * Parse the thing. Returns null on error.
   */
  public Thing parse(String s)
  ```

- **When Javadoc IS present, every parameter, return value, and declared exception is documented.** Add `@param` for
  each parameter, `@return` for every non-void return, and `@throws` for each declared / documented checked exception.
  The tags are not optional — if the method's parameters and return are obvious enough that tags would be tautological,
  the rule isn't "skip the tags", it's "the method shouldn't have Javadoc at all" (per the optional-omission list
  above). Tags should convey what the type and name alone don't: bound semantics, null behavior, ownership transfer,
  side-effects on inputs (e.g. `recycled when rotation produces a new instance`), the meaning of edge-case return values
  (`null when the EXIF byte-order field is malformed`), and the trigger conditions for each declared exception.

  ```java
  /**
   * Apply EXIF orientation to a bitmap, returning a correctly rotated bitmap.
   *
   * @param bmp         source bitmap; recycled when rotation produces a new instance
   * @param orientation EXIF orientation tag value (1..8); values outside that
   *                    range are treated as identity and the bitmap is returned
   *                    unchanged
   * @return correctly rotated bitmap (may be the same reference as bmp when
   *         orientation == 1 or out of range)
   */
  public static Bitmap applyOrientation(Bitmap bmp, int orientation)
  ```

- **In-method comments use `//`.** Always. Even multi-line ones. Don't use `/* ... */` for a one-liner tucked inside a
  method or branch — `// note` not `/* note */`. `/* ... */` has no place inside a method body.
- **Field-level comments use `//`**, not Javadoc. Fields tend to carry short annotations about invariants or lifecycle
  that read more naturally as inline notes than as a doc block.
- **Section dividers inside a class use `//`**, e.g. `// ── Bounds checks ──`. They group related members rather than
  document a single declaration.
- **No HTML or Javadoc inline tags in comments.** Do not write `<p>`, `<br>`, `<cite>`, `<code>`, `{@code ...}`, or
  `{@link ...}`. Also no HTML entities — write `>` / `<` / `&` literally, never `&gt;` / `&lt;` / `&amp;` / `&nbsp;`.
  This is an Android app — Javadoc is not rendered as HTML for end users, and the tags clutter the source. Use blank
  Javadoc lines (`*` on its own line) to separate paragraphs. Reference types by their bare name instead of wrapping
  them in `{@link}`.
- **Don't state the obvious.** `// increment counter` before `counter++` is noise. This rule applies equally to Javadoc
  — if the only thing the block would say is what the signature already says, omit it. Write comments that explain
  *why*, not *what*. Common patterns to delete on sight:
  - `// Log before` / `// Log after` above a loop whose body is a `Log.d`
  - `// Update X: do Y` above a one-liner whose call site already says X = Y
  - `// Find the X with the most votes` above a max-by-key loop with self-documenting variable names like `bestAngle` /
    `bestCount`
  - `// Check if tapping on existing point → remove it` above a loop body that calls `removeSelectionPointAt(...)` after
    a hit-test

Counter-examples (keep): comments that name a JPEG-spec literal (`// SOI` before `0xFFD8`), label a magic number with
its meaning (`int budget = 60_000; // JPEG thumbnail cap`), or explain a non-obvious invariant / threading concern / why
a defensive guard is in place.

## Constants

- Extract magic numbers and strings when they are repeated or when the literal itself doesn't tell the reader what it
  means.
  - `0xFFCBA6F7` appearing in six files → lives in `ThemeColors.MAUVE`.
  - `"Busy — try again"` appearing three times → class-private constant.
  - `8192` buffer size appearing twice → class-private constant.
- Use `SCREAMING_SNAKE_CASE` for **class-level** constants (`static final` fields). Method-local `final` values follow
  the ordinary variable-naming rules — camelCase, not SCREAMING — because they're scoped locals, not module-level
  constants.
- Add a trailing comment if the value's meaning is non-obvious.

### Canonical helpers

Some helpers exist specifically to be the **single chokepoint** for a recurring pattern. Reach for the helper rather
than re-rolling — both for correctness (the helper carries the documented edge-case behaviour) and so the chokepoint
stays load-bearing:

- **`util/DpToPx.toPx(int dp, float density)`** — every dp→px conversion. Uses `Math.round`, never `(int) (N * density)`
  truncation. The truncating variant collapses 1dp values to zero on density-0.75 screens, making thin dividers and 1px
  tick marks invisible. The Self-audit grep below catches any new `(int) (N * density)` regression.
- **`util/RotationMath.rotate / inverse`** — every 2D rotation around the image center. Sub-epsilon residual handling
  lives here.
- **`util/BitmapUtils.getMaxDecodePixels()` + `BitmapUtils.computeInSampleSize` + `BitmapUtils.initialize(Context)`**
  — the consistent-subsampling `BitmapFactory` decode sites route through this trio (load in
  `ImageLoadController.applyBytes` and HDR-save re-decode in `UltraHdrCompat.decodeHdrBitmap`). The cap is
  **device-adaptive**: `initialize` runs once from `MainActivity.onCreate`, reads
  `ActivityManager.getMemoryInfo().totalMem`, budgets 1/16 of total RAM (4 bytes per ARGB pixel), and clamps to
  `[32 MP, 512 MP]`. A 12 GB-RAM Samsung flagship gets ~187 MP (200 MP captures decode at `inSampleSize=1`,
  no quality loss); a 4 GB phone gets ~64 MP; a hypothetical 2 GB phone floors at 32 MP. Two-pass decode:
  bounds-only pre-pass reads the SOF dims, `computeInSampleSize` picks the smallest power-of-2 sample size
  that fits the subsampled bitmap within the cap. Power-of-2 contract matters — Android rounds non-power-of-2
  sampleSize values down internally, blowing the memory budget if a caller hand-rolls a "3" or "5". Both load
  and HDR-save re-decode sites pull from `state.getOriginalFileBytes()`, so they land on identical
  `inSampleSize` values — the HDR re-decode's coordinates stay self-consistent with the load-time subsampled
  `CropRender.cropW/cropH`. `EditAligner.reorientEdit` deliberately does NOT route through this trio: the
  graft splice requires full-resolution edit primary to match original's full-res metadata / gainmap; OOM
  is caught and surfaced as a clean "Couldn't decode the edit during reorientation" toast instead.
- **`metadata/JpegMarker`** — every JPEG marker byte (`SOI` / `EOI` / `SOS` / `RST_FIRST` / `RST_LAST` / `STUFFING` /
  `TEM`). Don't repeat the hex literal with an explanatory comment.
- **`metadata/JpegMarkerWalker.findPrimaryEoi(file, endBound)`** — every walk to find the byte just past the primary
  JPEG's EOI. Consolidates the SOS / EOI / RST / segment-length / overflow-guard logic that previously lived as three
  near-identical implementations across `CropExporter`, `GraftWriter`, and `GainMapExtractor`. Hardened against `segLen
  < 2`, `off + 2 + segLen` wrap-overflow, and truncated SOS headers.
- **`metadata/JpegSegment.XMP_HEADER`** — the canonical `"http://ns.adobe.com/xap/1.0/\0"` namespace identifier consumed
  by `isXmp()`, `HorizonDetector`, and `XmpItemLengthPatcher`.
- **`model/Format`** — JPEG / PNG enum carrying `extension()` and `mimeType()`. Never re-derive `".jpg"` /
  `"image/jpeg"`; never compare format with `String.equals`.
- **`crop/CropFitContext.of(...)`** — the rotated-clamp's pre-computed sin/cos/half-extents bundle. Replaced an
  11-parameter signature; reuse rather than passing the values through individually.
- **`crop/CropRender.of(...)`** — factory bundling (centerX, centerY, cropW, cropH, imgW, imgH, rotation) for the
  export pipeline. Final class with a private constructor; the public `of(...)` factory is the only construction path
  (private ctor closes the (W, H)-vs-(H, W) transposition footgun that a public record's positional canonical
  constructor would re-open). Has derived `srcX()` / `srcY()`.
- **`util/SafPaths`** — pure-string SAF document-ID parsing (`parentDocIdOf`, `lastSegmentSeparatorEnd`,
  `hasImageSignature`, `hasParentTraversalSegment`). Static, no Context — testable directly.
- **`model/StateBus`** — listener-dispatch + batch-suppression. CropState delegates here; never re-implement the batch
  protocol.

## Field ordering

Within a class, fields are grouped by declaration modifier, in this order:

1. `static final`
2. `static` (non-final)
3. `final` (instance)
4. Regular instance (non-static, non-final)

Within each tier, sort by **type** alphabetically, with **uppercase types (classes, interfaces) before lowercase
primitives** — `String` sorts before `boolean`, `float`, `int`, `long`. `byte[]` sorts with the lowercase primitives (it
starts with `b`).

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

## File layout

Top of file:

1. `package` declaration
2. Blank line
3. Imports, grouped: `android.*`, blank, `androidx.*`, blank, `com.*`, blank, `java.*`. Alphabetical within each group.
4. Blank line
5. Class-level Javadoc (required — see Comments)
6. Class declaration

Inside a class:

1. Nested types (interfaces, enums, records) — first, in declaration order
2. Blank line
3. Static fields — see Field ordering
4. Blank line
5. Instance fields
6. Blank line
7. Constructors
8. Methods — see Method ordering

## Imports

- **Never inline a fully-qualified type name.** If you need `android.provider.DocumentsContract`, add `import
  android.provider.DocumentsContract;` at the top. The *only* exception is a naming collision you can't otherwise
  resolve.
- No wildcard imports (`import foo.*;`).
- Imports are kept alphabetical within each group.

## Indentation and wrapping

- **Tabs only**, rendered at width 8.
- **Line length:** wrap at 120 display columns. Count tab as 8.
- **Continuation indent: exactly one tab deeper than the line that starts the statement.** This includes:
  - Method call arguments wrapped across lines
  - Method chains (`.foo().bar().baz()` laid out one-per-line)
  - Operator continuations (`&&`, `||`, `+`, `?`, `:`) starting a wrapped line
  - Boolean conditions spanning multiple lines
- **Never double-indent a wrap.** If a wrap sits two tabs deeper than the statement, that is a bug. Run the audit
  scripts in the Self-audit section before declaring done.
- **Don't wrap prematurely.** Only wrap when the rendered line (tab width 8) exceeds 120 columns. If a call fits under
  the limit as a one-liner, leave it as a one-liner — artificial wraps make diffs noisier and obscure the actual
  structure. When a wrap is forced, prefer refactoring into a cached local (`MaterialButton btnFoo =
  findViewById(...);`) over stretching one expression across three lines.
- **Fluent-chain alignment.** When a chain spans multiple lines, every `.foo()` sits at the same indent (one tab deeper
  than the receiver that started the chain):

  ```java
  new AlertDialog.Builder(this)
      .setTitle("Save")
      .setView(input)
      .setPositiveButton("OK", (dialog, which) -> save())
      .setNegativeButton("Cancel", null)
      .show();
  ```

  Not `setTitle` at one indent and `setView` at a deeper indent.
- Array initializers use K&R `= { ... };` placement, with elements indented one tab from the declaration:

  ```java
  private static final int[] PRESETS = {
      VALUE_A, VALUE_B, VALUE_C,
  };
  ```

## Language level

- **Java 21** (Android `compileSdk 36`, `minSdk 35`).
- Use modern language features where they genuinely clarify intent:
  - **Records** for immutable value types — see Records below.
  - **Switch expressions with arrow syntax** (`case X -> { ... }`) for multi-way dispatch on discrete values with 3+
    cases. No fall-through.
  - **`var`** for local variables whose type is obvious from the right-hand side and adds nothing to read.
  - **Pattern matching** for `instanceof` checks where it eliminates a cast.
  - **Diamond operator** (`new ArrayList<>()`) — never re-state type arguments when the compiler can infer them.
- Switch vs. if/else: use `switch` for 3+ discrete-value dispatch. Use `if`/`else if` for range checks, boolean
  combinations, or 1–2 cases.
- **`Math.clamp`** for range clamping. Do not hand-roll `Math.max(lo, Math.min(hi, x))` — `Math.clamp(x, lo, hi)` (Java
  21) is clearer and the argument order matches the intent ("value, low, high").

### Records

A class may become a `record` when **every field is effectively immutable** in practice — no setters, no internal
mutation, no external `.field = x;` assignments. `AspectRatio`, `ExportConfig`, `GridConfig`, `SelectionPoint`,
`JpegSegment`, `Graft`, `CropFitContext`, and `AiRegionDetector.AiMask` qualify; classes with internal state machines or
shared mutable buffers (`CropState`, the controller layer, `RotationRulerView`) do not. `CropRender` was record-eligible
but converted to a `public final class` with a private constructor + public `of(...)` factory after the record's public
canonical constructor (forced into (cropH, cropW, imgH, imgW) alphabetical order) became a transposition footgun
against the codebase's (W, H) convention — when factory ergonomics rule out a positional constructor, drop the record.
Mutable "config" records should expose `withXxx(value)` transformers alongside their accessors so callers can fold a
single-field change through `CropState.updateExportConfig` / `updateGridConfig` without building a fresh instance by
hand.

- Record components become method accessors: `point.x()` not `point.x`. When converting an existing class, grep for
  `.field` access sites and update them in the same commit.
- `byte[]` components are fine. Records use `Object.equals` for arrays (reference equality), which matches the
  pre-record behaviour of a plain class without `equals` overrides — no behavioural change.
- Instance methods (`isFree()`, `ratio()`) are allowed; records aren't just data bags.

## Method ordering

- Constructors first.
- Then methods by access level: `public` → `protected` → package-private → `private`.
- Within each access level, sort methods alphabetically by name. Do **not** pair getters with their setters — strict
  alphabetical ordering keeps the file scannable with `Ctrl-F` and avoids bikeshedding about what counts as a
  "property". A class with 20 getters followed by 20 setters is fine.
- Within each access level, static methods come **before** instance methods of the same access level. The factory /
  helper / pure-function surface lands at the top of each access block; instance methods (which depend on `this`)
  follow. Within the static and instance sub-blocks, alphabetical sort still applies.
- Android lifecycle overrides (`onCreate`, `onDestroy`, `onNewIntent`, `onDraw`, `onTouchEvent`) are ordinary protected
  methods — they sort alphabetically in their access-level section.
- **Sort order is case-sensitive ASCII** (`compareTo` on `String`, not `compareToIgnoreCase`). Uppercase letters
  sort before lowercase in the ASCII table — capital `'A'` (0x41) precedes lowercase `'a'` (0x61), so methods or
  fields whose names start with an uppercase letter come before their lowercase-prefix siblings within the same
  access tier. Concretely: `readU16` (capital `U` = 0x55) sorts BEFORE `readback...` (lowercase `b` = 0x62)
  because after the shared `read` prefix the next character favours uppercase. Pick this convention so the audit
  script and the IDE's default `Ctrl-F`/`Ctrl-Shift-F` ordering agree, and so a hand-edit can't oscillate between
  case-insensitive and case-sensitive readings of "alphabetical" depending on which side of `Aa` the next
  character lands on. The `scripts/audit.py method-order` check enforces this; section dividers that group
  related members (endian-dispatched routers in `ByteBufferUtils`, image-processing primitives in
  `HorizonDetector`) are explicit exceptions documented in their own files.

### Method references vs lambdas

Prefer a method reference when the lambda body is a single unadapted call:

```java
editorView.setOnZoomChangedListener(this::updateZoomBadge);   // preferred
editorView.setOnZoomChangedListener(() -> this.updateZoomBadge()); // avoid
```

Use a lambda when you need to transform arguments, capture extra state, or string multiple calls together.

**Lambda body length: 3 lines maximum.** A lambda whose body would exceed 3 lines of code should become a named private
method (passed via method reference, or invoked from a one-line lambda when arity adaptation is needed). The reasoning
is the same as for any nested anonymous-function construct: a 4+ line lambda is hard to read at the call site, hard to
debug (stack traces show synthetic names), and resists naming — extracting it gives the operation a name and a place to
attach Javadoc. Imperative loops inside a lambda body (and especially nested control flow) are a strong signal the body
should be a method.

### Streams vs imperative loops

Prefer a stream / collector when:
- the loop is a simple aggregation (`anyMatch`, `allMatch`, `count`, `sum`, `max`, `min`) over a Collection
- the loop transforms each element via a one-line lambda or method reference (`stream().map(...).toList()`)
- the resulting expression fits within the 3-line lambda cap

Stay imperative when:
- the loop has `break` / `continue` with non-trivial flow control
- per-iteration side effects span multiple statements
- the loop is performance-critical (per-pixel image processing, byte-walking metadata parsers, hot-path math) — stream
  overhead and boxing are not free
- a primitive-array (`byte[]`, `int[]`) walk would force boxing into a `Stream<Integer>` for no clarity gain

When in doubt, stay imperative. Streams are a clarity tool, not a goal.

## Scope minimization

- Fields that could be method-local variables should be. If a value lives entirely inside one method, don't lift it to a
  field.
- Methods that could be `private` should be. `public` only if the class contract actually exposes them.
- Classes/interfaces that could be package-private should be.
- A constant used in only one method should be declared `final` inside that method, not at class scope.
- A constant used in only one class should be `private static final` on that class.
- A constant used across multiple files should live in a shared utility class (`ThemeColors`, etc.) — but only then.
- Unused fields and dead code get deleted, not commented out.

## Self-audit

Before declaring a change done, these checks should come back empty. The fastest path is the
consolidated runner:

```bash
python scripts/audit.py   # runs over-cols, ignored-catches, static-first, method-order, adjacent-comment-styles, final-classes, reflow, lsloc
```

Individual subcommands (`python scripts/audit.py <name>`) are listed below alongside the awk
one-liners they replace. The awk forms are canonical under bash; the Python audit runner is the
cross-shell equivalent (Windows / PowerShell where escaping awk quoting is fragile).

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
grep -rnE '&(amp|gt|lt|nbsp|quot);' app/src/main/java app/src/test/java

# (int) (N * density) truncation — should be DpToPx.toPx(N, density). The
# truncating form drops 1dp values to zero on density-0.75 screens. The
# Math.round form in DpToPx is the documented fix.
grep -rnE '\(int\)\s*\([^)]*\*\s*density\)' app/src/main/java

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

# Single-line /** ... */ Javadoc on one physical line — expand to multi-line:
grep -rnE '^\s*/\*\*[^*]*\*/\s*$' app/src/main/java

# Lines exceeding the 120-column rendered limit (tab width 8). Pure stdlib
# awk computation so it matches what the renderer actually displays — bash
# grep counts UTF-8 bytes and false-positives on em-dashes / arrows. Fast
# fix: extract padding-side locals (padHor / padVer) so multi-arg
# setPadding(...) calls with DpToPx.toPx(...) wraps fit.
awk '{ n=0; for (i=1;i<=length($0);i++) { c=substr($0,i,1); if (c=="\t") n=int(n/8)*8+8; else n++ } if (n>120) print FILENAME":"NR" cols="n }' \
    $(find app/src -name '*.java')

# Python equivalent of the awk above for environments where escaping the awk
# quoting is fragile (Windows / PowerShell). Same tab=8 expansion, same output
# format. Either runner is canonical — pick the one that works in your shell.
python scripts/audit.py over-cols app/src

# // comments directly above a class or method declaration (use Javadoc instead):
awk '
  /^\s*\/\// { if (!in_comment) { in_comment=1; comment_ln=NR } next }
  /^\s*$/    { in_comment=0; next }
  {
    if (in_comment && $0~/^[[:space:]]*(public|private|protected|static|final|abstract|@Override|@Deprecated|void|boolean|byte\[\]|int|long|float|double|String|[A-Z][A-Za-z0-9_<>]*)\b.*\(/)
      print FILENAME":"comment_ln" // above method (use Javadoc)"
    else if (in_comment && $0~/^[[:space:]]*(public |private |protected )?(final |abstract |static )*(class|interface|enum|record)\b/)
      print FILENAME":"comment_ln" // above type decl (use Javadoc)"
    in_comment=0
  }
' $(find app/src/main/java -name '*.java')

# In-method /* ... */ inline block comments (use // instead):
grep -rnE '/\*[^*\n/][^\n]*\*/' app/src/main/java

# Static methods that appear after instance methods in the same access tier
# (violates the static-before-instance rule under Method ordering). Reports
# class, access tier, and the offending method names + line numbers. Empty
# output = clean.
python scripts/audit.py static-first app/src/main/java app/src/test/java

# Catches that swallow an exception without explaining why. Flags any catch
# whose parameter is named `ignored` or whose body is empty AND that has no
# // comment line in the body. Mirrors the "Intentionally swallowed
# exceptions" rule under Android / Java idioms — a reader should see at a
# glance which throws are expected by design (mid-keystroke parse failure,
# missing optional EXIF tag) versus accidentally lost. Empty output = clean.
python scripts/audit.py ignored-catches app/src/main/java app/src/test/java

# Concrete classes that should be `final` per Effective Java item 19. The
# scanner flags any class that is not abstract, not already final, and not
# extended (anywhere in our codebase, including via `new Foo() { ... }`
# anonymous-inner subclassing). The "SHOULD BE FINAL" section should be
# empty after each pass; entries there mean a new class was introduced
# without the modifier and isn't designed for extension.
python scripts/audit.py final-classes app/src/main/java app/src/test/java

# Identifiers containing a run of ≥ 2 uppercase letters mid-word (likely an
# acronym that should be camelCase — e.g. scanIFD → scanIfd, spinnerAR →
# spinnerAr). Catches non-constant identifiers only; SCREAMING_SNAKE and
# TAG are excluded by requiring a lowercase character before the run.
# False-positive filter: standard Android API methods (getFD, getXVelocity),
# single-letter axis suffixes followed by a real word (XFloat, YFloat), and
# Samsung JSON literal keys (isBrightnessIPE etc. are strings inside "…").
grep -rnE '\b[a-z][a-zA-Z0-9]*[a-z][A-Z][A-Z]+[a-zA-Z0-9]*\b' app/src/main/java \
    | grep -vE 'SCREAMING|TAG\s*=|getFD\(|getXVelocity|[XY]Float|[XY]Int|\\\\?"[^"]*[A-Z]{2,}'
```

Build must also be clean:

```bash
./gradlew.bat compileDebugJavaWithJavac
```

## Theme colors

The Catppuccin Mocha palette is defined in two places:

- **`app/src/main/res/values/colors.xml`** — canonical source. Use `getResources().getColor(R.color.mauve, null)`
  whenever a `Context` is available.
- **`com.cropcenter.util.ThemeColors`** — parallel `int` constants for code paths that don't have a `Context` handy
  (static helpers, `Paint` setup, `Bitmap` color fills, etc.). These mirror the XML values.

Do not inline a hex literal that corresponds to a theme color. If the color you want doesn't exist in `ThemeColors`, add
it there rather than copying the hex.

## Variable names

- **Self-documenting names.** `isLittleEndian` beats `le`. `centerX` beats `cx`. `halfWidth` beats `hw`. A short
  abbreviation "clarified by a comment" is worse than just typing the longer name.
- **Avoid one-letter and over-abbreviated names**, except for these standard idioms:
  - `i`, `j`, `k` — loop indices
  - `e` — **caught exception only** (`catch (Exception e)`). Don't reuse `e` for anything else (entry index, edge,
    element) — readers see it and assume exception. Pick a descriptive name instead.
  - `n` — count in a read loop (`int n = is.read(buf)`)
  - `x`, `y` — 2D coordinates (`setCenter(float x, float y)`)
  - `r`, `g`, `b` — RGB colour channels
  - `ctx` — `Context` (Android convention)
- Do not use `l` (easily confused with `1`) or `I`/`O` (confused with `0`).
- Android-specific short names that are acceptable because of widespread convention: `bmp` (Bitmap), `dp`/`dp4`/`dp8`
  (density-pixel conversions), `tv` (TextView) *inside tiny helper methods*, `lp` (LayoutParams) *inside tiny helper
  methods*, `pp` (Paint) *inside tight drawing loops*. "Tiny" means roughly ≤ 15 lines and a single obvious purpose. A
  170-line `show()` method with `colsLP`, `rowsLP`, `seekLP`, `spLP`, `btnLP` doesn't qualify — use `colsLayoutParams`
  or spell it out as a descriptive `widthRowLp` / `alphaRowLp` / etc.
- **Don't stack abbreviations.** Compounds of two short tokens compound their opacity: `srcGm`, `gmBmp`, `lblA`, `txtW`,
  `seekA` force the reader to decode two pieces at once. Spell at least one side out: `sourceGainmap`, `gainmapBitmap`,
  `alphaLabel`, `widthValueText`, `alphaSeekBar`. This is especially important in files ABOUT that data (a 350-line
  `CropEngine` with `cx`/`cy`/`cw`/`ch` locals is worse than the same file with `centerX`/`centerY`/`cropW`/`cropH`,
  because the abbreviations are the main content).
- **Cross-file consistency.** Pick one name per concept and use it everywhere. The image-axis midpoint is `imageMidX` /
  `imageMidY`, not `imgMidX` in one file and `imageMidX` in another; the un-rotated coordinate pair is `unrotatedX` /
  `unrotatedY`, not `unRotX` / `unRotY`; the image's screen-center is `imageScreenCenterX` / `imageScreenCenterY`, not
  `imgCx` here and `imgScreenCx` there. When you touch a file that uses an older spelling, rename to the canonical form
  in the same commit.

### Acronyms

Acronyms follow two different rules depending on where they appear:

- **In comments (Javadoc and `//`):** write the acronym in its canonical ALL-CAPS form — `EXIF`, `JPEG`, `PNG`, `HDR`,
  `UHDR`, `MPF`, `SEFT`, `ICC`, `SAF`, `IFD`, `APP1`, `UI`, `API`, `OS`, `IO`, `AR`, `ID`, `URI`, `URL`, `RGB`, `ARGB`,
  `YUV`, `DCT`, `MCU`, `ISO`, `GPS`, `HEIC`, `XML`, `JSON`. Mixed-case canonical forms (`sRGB`, `NaN`) keep their
  official casing. Plural forms use a lowercase trailing `s`: `IDs`, `JPEGs`, `URIs`.
- **In code identifiers (class, method, variable, field names):** treat the acronym as a regular word in camelCase /
  PascalCase. Only the first letter is capitalised: `mediaStoreId`, `getExifOrientation`, `JpegSegment`, `UiSync`,
  `uhdrInfo`, `safFileHelper`, `scanIfd`, `setupArSpinner`, `styleArLabel`, `spinnerAr`. **Never** `mediaStoreID`,
  `scanIFD`, or `getURL()` — those read as separate letters and break the camelCase word boundary that IDEs and humans
  parse on.
- **Constants (`SCREAMING_SNAKE_CASE`):** acronyms stay all-caps because the whole name is — `JPEG_SOI`,
  `APP1_MAX_SEGMENT_BYTES`, `FORMAT_JPEG`, `COL_ID`, `TIFF_HEADER_OFFSET`.
- **Protocol / spec literals** in comments keep the casing the spec uses even when it disagrees with this rule:
  `"Exif\0\0"` (the EXIF header string), `eXIf` (PNG chunk name), `JPEGInterchangeFormat` (EXIF/TIFF tag name). Leave
  them alone.

### Collision avoidance

When an outer scope already binds a short name (`left`, `top`, `width`, `height`…) and a renamed inner variable would
shadow it, pick a qualified form instead: `pixelLeft`, `pixelTop`, `cellWidth`, `thumbHeight`. Do **not** fall back to
one-letter abbreviations (`l`, `t`, `w`, `h`) to resolve the collision — that reintroduces exactly the opacity this rule
exists to prevent.

### Lambda and listener parameters

Android listener callbacks have real parameter names — use them. Do not abbreviate. Concrete conventions used in this
codebase:

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

Not `(d, w)`, `(b, c)`, `(sb, p, fu)`, `(s, a, b, c)`. The Android source uses the long names; match them.
