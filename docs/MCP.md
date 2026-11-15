# MCP server

`fast-compose mcp` runs an [MCP](https://modelcontextprotocol.io) server over stdin and stdout. AI agents use it to
check the Compose code they write and to see it rendered, without a Gradle build.

| Tool | What it does |
|---|---|
| [`check`](#check) | Runs previews off-screen and returns their problems as JSON |
| [`render`](#render) | Renders previews and returns a PNG image of each, with their problems |
| [`list_previews`](#list_previews) | Lists previews with their names, lines and settings, without running them |

The tools read the same files and use the same engine as the [commands](COMMANDS.md).

## Setup

The server is the `fast-compose` command with the argument `mcp`. Options after `mcp` apply to every tool call:

```bash
fast-compose mcp --classpath @app/build/preview-classpath.txt --res design/src/main/res
```

On Windows, MCP clients start servers without a shell and can't run `.bat` files directly, so the examples below
start it through `cmd`. Alternatively, give the full path to `fast-compose.bat`.

### Claude Code

```bash
claude mcp add --scope user fast-compose -- fast-compose mcp
```

`--scope user` makes it available in every project. Without it, the server is added only for the folder Claude Code
was started in.

For everyone working on a project, add it with `--scope project`; Claude Code then writes it to `.mcp.json`:

```json
{
  "mcpServers": {
    "fast-compose": { "command": "fast-compose", "args": ["mcp"] }
  }
}
```

On Windows:

```bash
claude mcp add --scope user fast-compose -- cmd /c fast-compose mcp
```

In Claude Code, `/mcp` shows whether the server is connected. Then ask in plain words, such as "check
ExplorerContent.kt" or "render the dark preview of HomeScreen.kt", and Claude calls the tools.

### Claude Desktop

In `claude_desktop_config.json` (**Settings → Developer → Edit Config**):

```json
{
  "mcpServers": {
    "fast-compose": { "command": "fast-compose", "args": ["mcp"] }
  }
}
```

On Windows:

```json
{
  "mcpServers": {
    "fast-compose": { "command": "cmd", "args": ["/c", "fast-compose", "mcp"] }
  }
}
```

### Cursor

In `.cursor/mcp.json` in the project, or `~/.cursor/mcp.json` for every project:

```json
{
  "mcpServers": {
    "fast-compose": { "command": "fast-compose", "args": ["mcp"] }
  }
}
```

### VS Code

In `.vscode/mcp.json`:

```json
{
  "servers": {
    "fast-compose": { "type": "stdio", "command": "fast-compose", "args": ["mcp"] }
  }
}
```

### MCP Inspector

To call the tools by hand and see their raw results:

```bash
npx @modelcontextprotocol/inspector fast-compose mcp
```

## Tools

Every tool takes the file to work on as `path` or `code`:

| Argument | Type | Effect |
|---|---|---|
| `path` | string | A Kotlin file, a folder or a glob such as `ui/**/*Screen.kt`; folders and globs take the files with `@Preview` functions |
| `code` | string | Kotlin source to use instead of reading a file |
| `recursive` | boolean | Searches a folder's subfolders too; default `false` |
| `preview` | string | Only the previews with this name or function name |

- Give `path` as an absolute path. A relative path is resolved from the server's working folder, which the MCP client
  chooses.
- With `code` and `path`, the code stands in for the file at `path`: it sees the other files and resources of that
  file's project, and problems are reported against that path. This checks an edit before it's saved.
- With `code` alone, the code is a file of its own with no project around it: it must declare everything it uses
  besides bindings.
- Files are read from disk on every call, so the tools always see the current files.

### check

Runs every preview off-screen and returns the problems found, as [JSON](OUTPUT.md#json) in one text block. See
[check](COMMANDS.md#check) for the kinds of problems.

```json
{ "name": "check", "arguments": { "path": "/path/to/app/src/commonMain/kotlin/app/ui/HomeScreen.kt" } }
```

```json
{
  "content": [
    { "type": "text", "text": "{\"files\":[{\"path\":\"/path/to/app/…/HomeScreen.kt\",\"previews\":[…],\"problems\":[{\"severity\":\"error\",\"message\":\"Unresolved reference 'Txt'. Did you mean 'Text'?\",\"file\":\"/path/to/app/…/HomeScreen.kt\",\"line\":48,\"preview\":\"Light\"}],\"output\":[]}],\"errors\":1,\"warnings\":0}" }
  ],
  "isError": false
}
```

`isError` is `false` when the check ran, whatever it found; the problems are in the JSON.

Checking unsaved code:

```json
{
  "name": "check",
  "arguments": {
    "path": "/path/to/app/src/commonMain/kotlin/app/ui/HomeScreen.kt",
    "code": "package app.ui\n\n@Preview\n@Composable\nfun HomePreview() { HomeScreen(state = previewState) }\n…"
  }
}
```

### render

Renders previews and returns, in order:

1. A text block with the [JSON](OUTPUT.md#json) report: previews, problems and each image's size in pixels.
2. For each image, a text block naming it, e.g. `Dark (HomePreview) in HomeScreen.kt, 822 × 1782 px`, then the image
   as an `image` block (`image/png`, base64).

Images aren't written to disk.

| Argument | Type | Default | Effect |
|---|---|---|---|
| `size` | string | `411x891` | Window size in dp for previews without `widthDp`/`heightDp`: `WxH`, `phone`, `foldable`, `tablet` or `desktop` |
| `dark` | boolean | the preview's | `true` renders every preview dark, `false` light |
| `fontScale` | number | the preview's | Font scale for every preview |
| `density` | number | `2` | Pixels per dp |
| `wait` | number | `300` | Milliseconds to let effects, animations and network images settle |

```json
{
  "name": "render",
  "arguments": {
    "path": "/path/to/app/src/commonMain/kotlin/app/ui/HomeScreen.kt",
    "preview": "Dark",
    "size": "tablet",
    "density": 1
  }
}
```

Images count toward the agent's context. For many previews, render them by name with `preview`, or with a lower
`density`.

### list_previews

Lists previews with their names, functions, lines and `@Preview` settings, without running them. Fast, and useful to
find what to render.

```json
{ "name": "list_previews", "arguments": { "path": "/path/to/app/src/commonMain/kotlin/app/ui", "recursive": true } }
```

## Errors

| Case | Result |
|---|---|
| Missing `path` and `code`, a path that doesn't exist, an invalid `size` | A tool result with `isError: true` and the reason as text |
| An unexpected failure while running | A tool result with `isError: true`, e.g. `IllegalStateException: …` |
| An unknown tool | A tool result with `isError: true` |
| An unknown method | JSON-RPC error `-32601` |
| A line that isn't JSON | JSON-RPC error `-32700` |
| A message that isn't a request object | JSON-RPC error `-32600` |

## Protocol

| | |
|---|---|
| Transport | stdio: one JSON-RPC 2.0 message per line, UTF-8 |
| Protocol versions | `2025-11-25`, `2025-06-18`, `2025-03-26`, `2024-11-05`; another requested version gets `2025-11-25` |
| Methods | `initialize`, `ping`, `tools/list`, `tools/call`; notifications are accepted and ignored |
| Capabilities | `tools` (the list doesn't change) |
| Server info | name `fast-compose`, title `Fast Compose CLI`, the CLI's version, website and icon |

- Requests are handled one at a time, in order.
- Only protocol messages go to stdout. Anything else the server, Compose or libraries print goes to stderr, which
  clients usually show as the server's log.
- The server stops when stdin closes.
- The server's `instructions` tell agents what the tools are for, and that a preview that renders may still fail to
  compile.

## Using it well

- After editing a file with previews, call `check` with its `path`; for an edit not yet saved, pass `code` too.
- Call `list_previews` to find previews, then `render` the ones that matter with `preview`.
- Stand-in warnings mean a library call ran as a placeholder; start the server with `--classpath` to run the real
  library code ([Projects](PROJECTS.md#library-code)).
- A preview that renders here may still fail to compile: the engine interprets the code and doesn't type-check it.
  Build before shipping.
