# agentkit-chat-ui

The console `agentkit-chat` serves. React, Vite, TypeScript and Tailwind, built into that
module's classpath under `dev/agentkit/chat/ui/`.

## You do not have to run any of this

`./mvnw verify` builds the console. Maven downloads a pinned Node into `node/`, runs `npm ci`
and `npm run build`, and copies `dist/` onto `agentkit-chat`'s classpath — so a clean clone
produces a runnable jar with one command and no npm step to remember.

```bash
./mvnw -pl agentkit-chat -am install     # builds the console too
./mvnw verify -Dfrontend.skip=true       # Java only: no network, no console in the jar
```

`-Dfrontend.skip=true` is for a machine with no network, or for iterating on the runtime rather
than the page. The jar it produces has no console in it, which is why it is opt-in.

## Working on the page

Two processes. The Java console on one side, Vite on the other:

```bash
# terminal 1 — the runtime, on whatever port your example starts
./mvnw -pl agentkit-examples-workbench-chat exec:exec

# terminal 2 — the page, with hot reload
cd agentkit-chat-ui
npm install
CHAT_API=http://localhost:8083 npm run dev
```

Vite serves the page on `:5173` and proxies `/api` to `CHAT_API` (default
`http://localhost:8080`). Without the proxy the page and the API are different origins and every
request is a CORS failure, which is the point at which people give up on hot reload and start
rebuilding the jar to change a colour.

```bash
npm run build       # what Maven runs
npm run typecheck   # tsc --noEmit
npm test            # vitest, also run by `./mvnw verify`
```

## What is committed

Sources and `package-lock.json`. Not `node/`, `node_modules/` or `dist/` — the built console is
a build product like any other in this repository, generated into
`target/classes/dev/agentkit/chat/ui` rather than checked in.

**Under the package, not at the root.** A classpath is flat, and `agentkit-examples-workbench`
ships its dashboard as `ui/index.html`. A module depending on both got whichever the loader
reached first — and it got the dashboard, so the chat console served the wrong product on the
right port while every test in the repository passed. Found by opening it in a browser; guarded
now by `TheChatServesItsOwnPageTest`, from the only module where the collision exists.

`npm ci` rather than `npm install` in the Maven build: it installs exactly the lockfile and
fails if `package.json` and the lock have drifted, which is the whole point of committing one.

## Two rules the page has to keep

**Nothing loads from another origin.** The console has to work on a machine with a model
endpoint and no internet, and a strict Content-Security-Policy is coming (#356). No CDN, no
webfont, no remote image. `TheConsoleIsBuiltIntoTheJarTest` checks the built page and its
stylesheet for this.

**The wire types match the Java ones.** `src/lib/types.ts` mirrors `View`'s kinds,
`ChatEvent.Type`, `Turn.State` and `Step.Kind`. They are hand-written and *checked*:
`TheWireTypesMatchTheJavaOnesTest` reads this file and fails if either side has something the
other does not. Adding a kind means touching two files, and forgetting the second is a red test
rather than a widget that silently never renders.
