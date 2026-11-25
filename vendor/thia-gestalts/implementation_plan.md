# Implementation plan

## Twelve-kind schema release

Use Draft 2020-12 and select the schema by record kind within the pinned release.

| Kinds | Schema path |
| --- | --- |
| Annotation, artifact, claim, entity, fact, flashcard, question, skill, task, topic | `<kind>/v1.json` |
| Learning event, note | `<kind>/v2.json` |

Register all versioned schema files under their declared `$id` values in a local registry. The release uses both `common/v1.json` and `common/v2.json`. The new common version recognizes all twelve kinds in typed references. Note and learning-event v2 use those expanded references. The earlier schema files remain usable by consumers pinned to the six-kind release. Versions select representations. Record equality still compares the exact `kind` and `id` strings. Producers and consumers implement the identity rules in [core.md](core.md).

## Validate offline

With CPython 3.14 and `jsonschema` 4.18 or later installed, run from the snapshot or repository root.

```sh
python3.14 scripts/validate_examples.py
```

The validator checks all schema documents, ensures there is a record example for every kind and resolves references by typed identity. It checks the original fixtures against their original schemas and the full example set against the release schemas. The rounding attempt, unscored Bézier engagement and image note are in [the original fixtures](examples/v1/records.json). The six new examples, including an offline-created flashcard, are in [the additional fixtures](examples/v2/records.json). The [identity vectors](examples/v1/identity.json) verify equality and distinctness with Unicode 16.0.0.

The desktop fixture check resolves an annotation to *Curve Sketchbook*, a question's opening event to the Bézier explorer activity, and two distinct bibliographic entries to that same artifact. Both entry titles derive the shared artifact ID under the existing name rules. The [desktop citation fixture](examples/v2/desktop.json) illustrates desktop-owned data. Only its artifact references use the shared schema. BibTeX fields, inbox state and scheduler implementation stay in their owning apps.

## Pin a release

A downstream consumer can vendor a source snapshot today. Save the fingerprint below with the dependency pin and verify it at the snapshot root. Copy all inputs to the fingerprint command, retaining their paths.

```sh
(
  export LC_ALL=C
  sha256sum */v[12].json core.md examples/v[12]/*.json scripts/validate_examples.py | sha256sum
)
```

Expected SHA-256 for this release is `b7f41c4542444d9548323b076a4d659606d0ab4b6e40de63f76509953c5ace1f`.

For a Git pin, use the full commit ID of the source revision that contains this release. Set `GESTALTS_URL` to the source repository URL and `GESTALTS_COMMIT` to that ID. From the consuming repository root, run the following once and commit the submodule pin.

```sh
git submodule add "$GESTALTS_URL" vendor/thia-gestalts
git -C vendor/thia-gestalts checkout --detach "$GESTALTS_COMMIT"
git add .gitmodules vendor/thia-gestalts
```

Fetch dependency files during setup. After setup the schemas, registry and validation run locally without a Thia runtime. When changing a published representation, add a new versioned path and schema ID.

## Downstream integration and open decision

Exercise the expanded fixtures in the browser producer and desktop consumer when those apps adopt this release. The checks here cover the shared representations and reference resolution.

The claim and citation examples expose an entity identity question. Should apps be able to reconcile independently created entity records for the same person or institution, and if so, what identifier or evidence should establish that link? This release uses stable UUIDs for entity records and leaves cross-app reconciliation open.
