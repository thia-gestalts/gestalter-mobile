# Thia Gestalts

The shared vocabulary exists so a learner’s work accumulates across independently built apps. Publish a small collection of JSON Schemas. Content authors and learners supply the actual topics, artifacts, facts and skills. Each app owns its additional shapes, UI, persistence and interpretation.

## Identity

Every record has `kind` and `id`. Compare both strings exactly. A typed record reference is the object `{"kind": "...", "id": "..."}` and resolves by that pair. A schema revision describes a representation and plays no part in equality.

### Topics and artifacts

Topics collapse by normalized name across publishers. Artifacts collapse by normalized name across importers. An artifact and a topic can share the same `id` and remain distinct records. Store the original display spelling in `name`. A consumer that coalesces records can choose which display spelling to show. Topics can be created offline from any valid name.

Derive the v1 `id` from `name` using Unicode **16.0.0** for every step.

1. Reject unpaired surrogates. Normalize the Unicode scalar sequence to NFC. [Unicode 16.0.0 normalization](https://www.unicode.org/reports/tr15/tr15-56.html)
2. Replace each maximal run of the Unicode `White_Space` property with one U+0020, then remove leading and trailing U+0020. Reject an empty result. [Unicode 16.0.0 property list](https://www.unicode.org/Public/16.0.0/ucd/PropList.txt)
3. Apply default full case folding by mapping each code point with the `C` and `F` entries from `CaseFolding.txt`. Leave unmapped code points as they are. Normalize the folded string to NFC again. [Unicode 16.0.0 case folding data](https://www.unicode.org/Public/16.0.0/ucd/CaseFolding.txt)
4. Encode the result as UTF-8 without a BOM. Write each byte as two lowercase hexadecimal digits and prepend the literal ASCII prefix `name:`.

Punctuation, accents and other characters participate in identity according to these steps. For example, `A-B` and `A B` have different identities. A human can qualify an ambiguous name, such as “Rounding (CAD)”. The schema validates the ID grammar. Producers and consumers also verify that the normalized name derives the stated ID. [Identity examples](examples/v1/identity.json)

An edit with the same derived ID changes only the display spelling. When a rename changes the ID, move all references to the renamed record's new typed identity. When splitting a record, create a qualified identity and move only the selected references. Retain each event's `origin` and the provenance of imported records so a correction can identify that subset. Updating a reference preserves the identity of the referring record and the occurrence it represents.

### Facts, skills, notes and occurrences

A published fact or skill can use `key:<namespace>:<local-key>`. To form the namespace, put the lowercase ASCII labels of a DNS name the publisher controls in reverse order, such as `org.example.math` for `math.example.org`. Use at least two labels, each 1–63 letters, digits or hyphens, with a letter or digit at both ends and at most 253 characters including dots. The publisher assigns a local key made of one or more slash-separated segments. Each segment has lowercase ASCII letters and digits, with single hyphens allowed between groups. For example, use `key:org.example.math:rounding/nearest-ten`. Compare the key as written. Keep it stable through display edits and reserve it permanently for that target, including when rotating domains. For inventory-derived keys, the publisher documents the inventory identifiers and operation. Recognizing a flag and locating the country use different skill IDs.

Mint a fresh lowercase, hyphenated UUIDv4 for each personal note, personal fact or skill, and individual learning event. Two independently created notes remain distinct even when their text matches. Two occurrences remain distinct even with the same activity, targets and millisecond timestamp. Save the event ID at creation and reuse it for retries or imports of that same event. [UUIDv4 specification](https://www.rfc-editor.org/rfc/rfc9562.html#section-5.4)

Mint a fresh UUIDv4 for each new annotation, question, entity, claim, flashcard and task. Preserve the ID when editing or copying that same record between apps. An entity's display name can change independently of its identity. Two entity records with the same display name have separate identities if their UUIDs differ.

## Scope

Keep topics and artifacts lean enough that auto-collapse is useful. Bibliographic entries, citation keys, edition details and reconciliation belong to specialist apps. Multiple entries can refer to one artifact. Distinguish a topic used for navigation, a set used for counting, and evidence about a fact or skill. Exploring a visualization can be meaningful without earning a score.

An event records UTC epoch milliseconds, its source app and app-local activity, and one or more typed targets. A topic target supplies context. Attempts target at least one fact or skill. A supplied score is a number from 0 to 1 for the whole attempt. Omit it for unscored interactions. Zero is an observed score. Apps own sets, aggregation and interpretation.

A note can store its title, Main Markdown (`text`), Extra Markdown, aliases and tags separately. Relationships refer to records by typed identity and can carry a relation label. A note may have content in any of these fields or in its attachments. Older body-only notes retain their Markdown in `text`. [Note examples](examples/v3/records.json)

A note can refer to stored image bytes by attachment UUID and image media type. Keep that UUID stable when moving or syncing the attachment. Apps own the bytes and the mapping from UUID to storage. [Example records](examples/v1/records.json)

An annotation points to one record and can include a human-readable locator such as a page number. A question can name sources that opened it or answered it. To record an activity occurrence as a source, reference its learning event. The event's `origin` identifies the activity within its app. Merely listing a question among an event's targets describes what the event addressed. The question's `openedBy` and `answeredBy` fields express the source's role.

A claim can attribute its assertion to entities and cite supporting records. A flashcard has a front and back and can target facts. A task gives an instruction and a checkable outcome and can target skills. Apps own inbox workflow, review history and scheduler state, including FSRS parameters and due dates. [Additional examples](examples/v2/records.json)
