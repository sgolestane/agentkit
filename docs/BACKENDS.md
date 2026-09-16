# Backends

Operational detail for running AgentKit against a hosted model backend. The README
covers what each backend *is* and which one to pick; this covers what you have to
set up once you have picked.

## Amazon Bedrock (`agentkit-llm-bedrock`)

Depend on `dev.agentkit:agentkit-llm-bedrock` instead of `agentkit-llm-anthropic`
— it reuses the same adapter, adding the Bedrock backends and a `ModelResolver`,
the seam that maps the logical model ids your agents use onto the concrete wire
ids a backend wants.

There are **two invocation backends**, and the one you pick decides whether
application inference profiles apply:

| | `Bedrock.llmClient()` — Mantle | `Bedrock.invokeModel()` — InvokeModel |
|---|---|---|
| AWS surface | `bedrock-mantle` | `bedrock-runtime:InvokeModel` |
| IAM action | `bedrock-mantle:CreateInference` on a *project* | `bedrock:InvokeModel` on the model/profile |
| Cost attribution | per Bedrock project | per **application inference profile** |
| Application inference profiles | not applicable | **this is where they apply** |

If you use **application inference profiles** (account-specific ARNs for cost
attribution), you need the InvokeModel backend — profiles have no effect on the
Mantle path.

```java
// Mantle (recommended for new integrations, no inference profiles):
LlmClient llm = Bedrock.llmClient();            // AWS_REGION + default credential chain
// AgentConfig.builder(BedrockModels.CLAUDE_OPUS_4_8)...     // "anthropic.claude-opus-4-8"

// InvokeModel, no profiles — invoke a cross-region inference-profile id directly.
// (The bare "anthropic.claude-opus-4-8" is NOT on-demand invokable — use the geo form.)
LlmClient direct = Bedrock.invokeModel();
// AgentConfig.builder(BedrockModels.US_CLAUDE_OPUS_4_8)...  // "us.anthropic.claude-opus-4-8"

// InvokeModel + application inference profiles — discover ARNs, resolve logical ids to them:
try (BedrockClient control = BedrockClient.create()) {
    ModelResolver resolver = InferenceProfiles.resolver(control);  // lists your app profiles
    LlmClient llm2 = Bedrock.invokeModel(resolver);
    // AgentConfig model is the bare "anthropic.claude-opus-4-8"; each call is rewritten to your ARN.
}
```

If you obtain ARNs another way (config, SSM, your own discovery), skip discovery
and pass `ModelResolver.ofMap(yourMap)`.

In a **shared account** many application profiles can wrap the same model (e.g.
one per engineer). Discovery keys by model id, so those collide and it keeps the
first — which may not be one you can invoke. Pass a filter to narrow the roster
to yours: `InferenceProfiles.resolver(control, s -> s.inferenceProfileName() != null && s.inferenceProfileName().startsWith("eng-me-"))`.

**Run a demo on Bedrock.** The example `main`s honour `AGENTKIT_BACKEND=bedrock`
and resolve AWS credentials/region through the standard chain — including a named
**SSO** profile. By default the demo uses the Mantle backend; set
`AGENTKIT_BEDROCK_INVOKE_MODEL=true` for the InvokeModel backend, or
`AGENTKIT_BEDROCK_DISCOVER_PROFILES=true` to additionally discover your
application inference profiles (which implies InvokeModel):

```bash
aws sso login --profile my-bedrock-profile         # ensure a valid session
export AWS_PROFILE=my-bedrock-profile
export AWS_REGION=us-west-2                         # the region your profiles live in
export AGENTKIT_BACKEND=bedrock
export AGENTKIT_BEDROCK_DISCOVER_PROFILES=true      # InvokeModel + map logical ids → your profile ARNs
export AGENTKIT_BEDROCK_PROFILE_PREFIX=eng-me-      # in a shared account, keep only your profiles

./mvnw install -DskipTests                          # once — publish the modules locally
# exec:exec forks a JVM, so the demo's main() runs with the module's own classpath
./mvnw -f agentkit-examples/pom.xml exec:exec \
    -Dexec.mainClass=dev.agentkit.examples.EndToEndAgent
```

If you already know your profile ARN, skip discovery entirely and invoke it
directly: `export AGENTKIT_BEDROCK_MODEL=arn:aws:bedrock:us-west-2:123:application-inference-profile/abc`.

The InvokeModel path needs `bedrock:InvokeModel` on the model (or on your
application-inference-profile ARN), plus `bedrock:ListInferenceProfiles` when
`AGENTKIT_BEDROCK_DISCOVER_PROFILES=true`. The Mantle default instead needs
`bedrock-mantle:CreateInference` on the project.

Two things matter in that command: run against the module's pom (`-f
agentkit-examples/pom.xml`, or `cd agentkit-examples && ../mvnw ...`) so the goal doesn't
run against the aggregator root, and use **`exec:exec`** (forks a JVM), not
`exec:java` — the in-process runner's classloader mishandles the AWS SDK and fails
with a spurious *"the 'sso' service module must be on the class path"* even though
the jars are present. The one-time `./mvnw install` lets the module resolve its
sibling jars from your local repository.

The `agentkit-examples` module bundles the AWS `sso`/`ssooidc` modules so an SSO
profile resolves out of the box; a library that uses SSO must add those two AWS
SDK artifacts itself. No `ANTHROPIC_API_KEY` is needed on the Bedrock path.

## OpenRouter (`agentkit-llm-openrouter`)

Depend on `dev.agentkit:agentkit-llm-openrouter`, set `OPENROUTER_API_KEY`, and
see the README's
[Running on OpenRouter](../README.md#running-on-openrouter-many-providers)
section. OpenRouter needs no account-level setup beyond the key.
