<!--
Thanks for contributing! Please read CONTRIBUTING.md first — FableFactions has hard
architectural rules that the build enforces. Small, focused PRs merge fastest.
-->

## What does this PR do?

<!-- One or two sentences. Link the issue it closes, if any. -->

## Checklist

- [ ] `./gradlew build` passes locally — all verification gates
      (`verifyDowngrade`, `verifyJdk8Api`, `verifyRelocation`, `verifyKernelPurity`,
      `verifyProbeIsolation`, `verifyDescriptorFloor`, `verifyNoStickyGetstatic`) and
      all test suites are green.
- [ ] Tests added or updated for every behavior change (jqwik property tests for kernel
      rule changes).
- [ ] Docs updated where the change affects them (`docs/ARCHITECTURE.md` /
      `docs/CONTRACTS.md` are normative — a change that contradicts them needs the doc
      changed in the same PR, with justification).
- [ ] **No new dependencies**, and no version bumps to the pinned storage stack
      (HikariCP 4.0.3 / H2 1.4.200 / mysql 8.0.33 — AM-10). New deps require prior
      discussion.
- [ ] Commit messages follow conventional commits (`feat:` / `fix:` / `refactor:` /
      `style:` / `docs:` / `test:` / `build:`).

## Thread rules

<!--
Which thread(s) does your new/changed code run on, and why is that correct?
E.g.: "listener body — snapshot read + intent submit only", "fable-kernel writer via
Reducer", "fable-storage projector", "player region thread via Scheduling.runOn".
Listeners/commands must not touch JDBC, the journal, or kernel-state construction.
-->

- Runs on:
- Mutations (if any) flow through:

## Parity / deviation

<!--
For behavioral changes: cite the docs/research/ref-*.md section that specifies this
behavior, OR explain the intentional deviation and why. Delete this section for
non-behavioral changes.
-->
