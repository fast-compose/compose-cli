# Output

`check`, `render` and `list` print the same report, as text by default or as JSON with `--json`. The MCP tools return
the JSON form.

## Text

```
app/src/commonMain/kotlin/app/ui/HomeScreen.kt
  error    HomeScreen.kt:48  Unresolved reference 'Txt'. Did you mean 'Text'?  [Light]
  warning  Theme.kt:60  'SyntaxColors' (io.nemo.model) has no binding; a stand-in is used, so the preview may differ  [Light]
  preview  Light (HomePreview)  line 42  →  previews/HomeScreen-Light.png (822 × 1782 px)
  preview  Tablet (HomePreview)  line 44  ·  1280 × 800 dp  ·  dark  →  previews/HomeScreen-Tablet.png (2560 × 1600 px)
  output   Loaded 3 items
1 file(s), 1 error(s), 1 warning(s)
```

Per file, its path relative to the current folder, with `/` separators, then:

| Line | Content |
|---|---|
| `error`, `warning` | The file name, line and column when known, the message, and in brackets the preview that hit it at runtime |
| `preview` | The preview's name, its function when different, its line, its `@Preview` settings, and for `render` the image path and size |
| `no @Preview functions` | The file has none (`render` and `list`) |
| `output` | A line the previews printed with `println` |

`check` leaves out the `preview` lines. The last line has the totals. A console that can't show `→`, `×` or `·`, such
as a Windows console on code page 437, gets `->`, `x` and `-` instead.

- `--ignore-warnings` leaves out warnings, and they aren't counted.
- `--quiet` leaves out `error`, `warning` and `output` lines; the totals still count problems.

## JSON

```bash
fast-compose render HomeScreen.kt --json -o previews
```

```json
{
  "files": [
    {
      "path": "app/src/commonMain/kotlin/app/ui/HomeScreen.kt",
      "previews": [
        {
          "name": "Light",
          "function": "HomePreview",
          "line": 42,
          "fontScale": 1.0,
          "darkTheme": false,
          "showBackground": true,
          "image": "previews/HomeScreen-Light.png",
          "widthPx": 822,
          "heightPx": 1782
        }
      ],
      "problems": [
        {
          "severity": "error",
          "message": "Unresolved reference 'Txt'. Did you mean 'Text'?",
          "file": "app/src/commonMain/kotlin/app/ui/HomeScreen.kt",
          "line": 48,
          "preview": "Light"
        }
      ],
      "output": ["Loaded 3 items"]
    }
  ],
  "errors": 1,
  "warnings": 0
}
```

With `--json`, stdout carries only the JSON. Anything else printed during the run goes to stderr, so
`fast-compose render --json 2>nul > report.json` (`2>/dev/null` on macOS and Linux) gives clean JSON. When no files
match, the report is `{"files": [], "errors": 0, "warnings": 0}`.

### Report

| Field | Type | Content |
|---|---|---|
| `files` | array | One [file](#file) per file the command ran on, in order |
| `errors` | number | Errors in all files |
| `warnings` | number | Warnings in all files; 0 with `--ignore-warnings` |

### File

| Field | Type | Content |
|---|---|---|
| `path` | string | Path relative to the current folder, with `/` separators; absolute in MCP results |
| `previews` | array | The file's [previews](#preview) in source order, or those `--preview` names |
| `problems` | array | [Problems](#problem) in the order found, each once; empty with `--quiet` |
| `output` | array of strings | Lines printed with `println`, each once; empty with `--quiet` and for `list` |

### Preview

| Field | Type | Content |
|---|---|---|
| `name` | string | `@Preview(name = …)`, or the function's name |
| `function` | string | The `@Composable` function |
| `line` | number | 1-based line of the function |
| `group` | string | `@Preview(group = …)`; only when set |
| `widthDp`, `heightDp` | number | From `widthDp`/`heightDp` or `device`; only when set |
| `locale` | string | `@Preview(locale = …)`; only when set |
| `fontScale` | number | `@Preview(fontScale = …)`, 1.0 by default |
| `darkTheme` | boolean | True for `uiMode = UI_MODE_NIGHT_YES` |
| `showBackground` | boolean | `@Preview(showBackground = …)` |
| `image` | string | Where `render` wrote the image, as `-o` was given, with `/` separators; not in MCP results |
| `widthPx`, `heightPx` | number | The image's size in pixels; only for rendered previews |

The settings are the preview's own: `--dark`, `--light` and `--font-scale` change the images, not these fields. A
preview that crashed has no image fields.

### Problem

| Field | Type | Content |
|---|---|---|
| `severity` | string | `error` or `warning` |
| `message` | string | What's wrong, without the location |
| `file` | string | The file it's in: the previewed file or another one it uses, relative to the current folder; absolute in MCP results |
| `line` | number | 1-based line, when known |
| `column` | number | 1-based column, for parse errors |
| `preview` | string | The preview that hit it, for problems found at runtime |

Errors make the exit code 1. Warnings point at code that runs but may look different from the app, such as stand-ins
for library calls or a missing resource.

## Using the JSON

With [jq](https://jqlang.org):

```bash
# Every error as file:line: message
fast-compose check -r app/src --json 2>/dev/null \
  | jq -r '.files[].problems[] | select(.severity == "error") | "\(.file):\(.line): \(.message)"'

# Image paths of every rendered preview
fast-compose render -r app/src -o previews --json 2>/dev/null | jq -r '.files[].previews[].image'

# Previews per file
fast-compose list -r app/src --json 2>/dev/null | jq '.files[] | {path, previews: [.previews[].name]}'
```

In PowerShell:

```powershell
$report = fast-compose check -r app/src --json 2>$null | ConvertFrom-Json
$report.files.problems | Where-Object severity -eq 'error' | Format-Table file, line, message
```
