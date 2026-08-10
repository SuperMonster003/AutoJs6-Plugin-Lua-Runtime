# Frozen protocol artifact intake

This independent repository consumes three repository-local AARs:

- `common-plugin-api.aar`
- `protocol-wire-api.aar`
- `lua-runtime-api.aar`

They are intentionally absent from the scaffold. The Lua API currently exists
only as an uncommitted module in the host worktree, so copying its current
output would not provide immutable provenance. The scaffold has not been
compiled, and none of the three protocol AARs has been generated and
synchronized for this repository.

After the protected soak ends:

1. Commit and gate the three source modules in the host repository.
2. Run `tools/stage_protocol_artifacts.ps1` with the clean checkout and
   expected revision. The script rebuilds all three modules with rerun tasks
   and no build cache before staging; it does not trust pre-existing outputs.
3. Review the generated SHA-256 values in
   `protocol-artifacts.lock.json`.
4. Commit the three AARs only together with their complete lock.

The App build fails fast when any AAR is missing. Release builds must never
resolve these protocol classes from a mutable sibling path, Maven snapshot, or
an arbitrary developer cache.

The repository input gate also requires an exact schema-1 lock, the literal
`AutoJs6` source repository, a nonzero lowercase 40-character Git revision,
the exact three module/file mappings, and matching lowercase SHA-256 values.
Unknown lock fields, extra AARs, and symlinked AARs fail closed. Repository
ignore rules explicitly admit only `protocol/*.aar`; this is tracking policy,
not evidence that the currently absent artifacts have been staged.

The static verifier also rejects duplicate JSON members, but a syntactically
valid 40-character revision plus self-consistent AAR digests does not prove
that the commit exists or that the files were built from it. That provenance
gate remains the clean-checkout staging workflow and its later Gradle/build
evidence.
