---
name: subagent-orchestration
description: 'Splits implementation work between a thinking orchestrator and implementor subagents: Claude Opus 5.5 plans, slices, reviews and verifies; Claude Sonnet 5.5 writes the code. Use for any multi-step or multi-file change, feature work, refactors, bug fixes that touch more than one file, "orchestrate", "delegate to subagents", "use the implementor", or whenever the Orchestrator agent is active. Not for questions, reviews, or a one-line edit outside Orchestrator mode.'
argument-hint: 'Describe the change to plan and delegate'
---

# Subagent Orchestration

## Roles

| Role | Agent | Model | Owns |
| --- | --- | --- | --- |
| Orchestrator | `Orchestrator` (main session) | Claude Opus 5.5 | understanding, plan, slicing, packets, diff review, final gate |
| Implementor | `Implementor` (subagent) | Claude Sonnet 5.5 | code edits for one packet, its targeted check, the report |

A skill cannot pin a model, so it lives in agent frontmatter:
[orchestrator.agent.md](../../agents/orchestrator.agent.md) and
[implementor.agent.md](../../agents/implementor.agent.md). VS Code agent files have no effort
field, so both run at the effort set in the model picker; the Claude Code twins in
`.claude/agents/` pin it with `effort:`. Run from any other agent, this skill still sends edits to
Sonnet through `Implementor`, but the thinking happens on whatever model the picker has selected.

A cheaper variant runs the same procedure: [orchestrator-lite.agent.md](../../agents/orchestrator-lite.agent.md)
(Claude Opus 5.5) dispatches [implementor-lite.agent.md](../../agents/implementor-lite.agent.md)
(Claude Haiku 5.5), with smaller packets and a Sonnet 5.5 escalation step instead of Opus 5.5.

## When to use

- The change touches two or more files, needs a plan, or splits into independent slices.
- In Orchestrator mode, every change, even a one-liner: the orchestrator has no edit tool.

Skip it for questions, reviews, and (outside Orchestrator mode) a single obvious edit.

## Procedure

1. **Understand.** Read the request, then the code it touches (`graphify query` first, then the
   files). Hand wide searches to `Explore`, but read yourself what the plan depends on. Ask the
   user when an ambiguity would change the design; otherwise state the assumption.
2. **Slice.** Break the work into packets and track them in the todo list. A packet has one goal
   and an observable acceptance check, owns a set of files no other in-flight packet touches, and
   fits one subagent run (roughly 1–5 files). Order by dependency: contracts and types before
   callers, tests with the code they cover.
3. **Write the packet** from the template below. The implementor is stateless and has seen none of
   this conversation, so everything it needs goes in the prompt: paths, line ranges, the exact
   contract, and the repo rules that bite here.
4. **Dispatch** with the subagent tool: `agentName: "Implementor"`, a 3–5 word `description`, the
   packet as `prompt`. Put independent packets in one tool block so they run in parallel;
   dependent ones go one at a time.
5. **Review every return.** A "done" is a claim, not evidence:
   - read the diff of the owned files (`git diff -- <files>`) against ACCEPTANCE and OUT OF SCOPE;
   - reject edits outside OWN, weakened checks (`@ts-ignore`, `eslint-disable`, skipped tests,
     loosened assertions), stubs, and unrequested refactors;
   - on a defect, re-dispatch a corrected packet that quotes the defect. Never resend it unchanged.
6. **Gate.** Once every packet has landed and no implementor is running, run the repo checks the
   surface needs, one at a time: `npm run lint`, `npm run test`, `npm run build`, targeted e2e.
   Redirect long runs to a log and read the log. A failure becomes a new fix packet.
7. **Close.** Run `graphify update .`, then report what changed (file links), what was verified,
   and what was deferred.

## Packet template

```text
GOAL: <one sentence: the outcome, not the steps>
CONTEXT: <why, and the design decisions already made>
READ FIRST: <path#Lstart-Lend: what to look for there>
OWN: <paths the implementor may edit; everything else is read-only>
CONTRACT: <signatures, types, props, behaviour that must hold>
REPO RULES: <the few copilot-instructions rules that bite in this packet>
ACCEPTANCE: <observable checks, e.g. "foo.test.ts asserts X and passes">
VERIFY: <exact command, e.g. npx vitest run src/utils/foo.test.ts>
OUT OF SCOPE: <what not to touch or "improve">
RETURN: STATUS (done | partial | blocked), FILES CHANGED, VERIFY output, DEVIATIONS, OPEN QUESTIONS
```

## Parallel safety

- OWN sets must be disjoint. Two writers on one file silently clobber each other.
- Parallel implementors run file-scoped checks only. Whole-repo commands belong to the gate in
  step 6: a second command in a busy terminal interrupts the first.

## Escalation

1. `blocked`, or the same packet fails review twice: the packet is at fault. Add the missing
   context or slice it smaller.
2. Still failing, and the difficulty is genuine reasoning rather than missing context: re-dispatch
   once with `model: "Claude Opus 5.5 (copilot)"`, and say so in the final report.
3. A third failure: stop and bring it to the user with what was tried.

## Anti-patterns

- The orchestrator "just quickly" editing a file, which collapses the role split.
- Packets that say "as discussed" or "see above"; the implementor saw nothing.
- "Figure out and fix" packets that hand the thinking to the implementor. Plan first.
- Accepting the implementor's summary in place of reading the diff.
