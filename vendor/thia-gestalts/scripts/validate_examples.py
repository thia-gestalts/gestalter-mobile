"""Validate the vocabulary release against local schemas and fixtures."""

import json
import re
import unicodedata
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource


ROOT = Path(__file__).resolve().parents[1]
EXAMPLES = ROOT / "examples"
RECORD_VERSIONS = {
    "annotation": "v1",
    "artifact": "v1",
    "claim": "v1",
    "entity": "v1",
    "fact": "v1",
    "flashcard": "v1",
    "learning-event": "v2",
    "note": "v3",
    "question": "v1",
    "skill": "v1",
    "task": "v1",
    "topic": "v1",
}
REFERENCE_ARRAYS = {
    "claim": ("attributedTo", "supportingReferences"),
    "flashcard": ("targets",),
    "learning-event": ("targets",),
    "note": ("related",),
    "question": ("openedBy", "answeredBy"),
    "task": ("targets",),
}
WHITE_SPACE = re.compile(
    "[\u0009-\u000d\u0020\u0085\u00a0\u1680\u2000-\u200a"
    "\u2028\u2029\u202f\u205f\u3000]+"
)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def identity(record):
    return record["kind"], record["id"]


def name_id(name):
    name = unicodedata.normalize("NFC", name)
    name = WHITE_SPACE.sub(" ", name).strip(" ")
    if not name:
        raise ValueError("A normalized name must be nonempty")
    folded = unicodedata.normalize("NFC", name.casefold())
    return "name:" + folded.encode("utf-8").hex()


def record_references(record):
    if record["kind"] == "annotation":
        yield record["target"]
    for field in REFERENCE_ARRAYS.get(record["kind"], ()):
        yield from record.get(field, [])


def validate_desktop(records, resolve, validate_reference):
    # Bibliographic data is an app fixture. Only its artifact link is shared.
    citations = read_json(EXAMPLES / "v2" / "desktop.json")["citations"]
    if len(citations) != 2 or len({entry["citationKey"] for entry in citations}) != 2:
        raise ValueError("Expected two distinct desktop citation records")

    for entry in citations:
        reference = entry["artifact"]
        validate_reference(reference)
        artifact = resolve(reference)
        if artifact["kind"] != "artifact" or artifact["id"] != name_id(entry["fields"]["title"]):
            raise ValueError("Expected the citation title to resolve to its named artifact")

    shared_artifact = citations[0]["artifact"]
    if citations[1]["artifact"] != shared_artifact:
        raise ValueError("Expected both citations to reference the same artifact")
    if not any(
        record["kind"] == "annotation" and record["target"] == shared_artifact
        for record in records
    ):
        raise ValueError("Expected an annotation attached to the shared artifact")

    opening_events = [
        resolve(reference)
        for record in records
        if record["kind"] == "question"
        for reference in record.get("openedBy", [])
        if reference["kind"] == "learning-event"
    ]
    if not any(event["origin"] == {
        "app": "org.example.curves", "activity": "bezier-explorer"
    } for event in opening_events):
        raise ValueError("Expected the Bézier explorer activity to open a question")


def main():
    if unicodedata.unidata_version != "16.0.0":
        raise SystemExit("Identity fixtures require Unicode 16.0.0 (such as CPython 3.14)")

    schemas = {
        (path.parent.name, path.stem): read_json(path)
        for path in ROOT.glob("*/v[0-9]*.json")
    }
    for schema in schemas.values():
        Draft202012Validator.check_schema(schema)
    registry = Registry().with_resources(
        (schema["$id"], Resource.from_contents(schema)) for schema in schemas.values()
    )
    validators = {
        (kind, version): Draft202012Validator(schema, registry=registry)
        for (kind, version), schema in schemas.items()
    }

    def validate(record, version=None):
        kind, record_id = identity(record)
        validators[kind, version or RECORD_VERSIONS[kind]].validate(record)
        if kind in {"topic", "artifact"} and record_id != name_id(record["name"]):
            raise ValueError(f"{kind} {record_id} does not match its name")

    original_records = read_json(EXAMPLES / "v1" / "records.json")
    for record in original_records:
        validate(record, "v1")
    records = original_records + read_json(EXAMPLES / "v2" / "records.json")
    records += read_json(EXAMPLES / "v3" / "records.json")
    for record in records:
        validate(record)
    if {record["kind"] for record in records} != RECORD_VERSIONS.keys():
        raise ValueError("Expected an example record for each release kind")
    events = [record for record in records if record["kind"] == "learning-event"]
    if not any(event["type"] == "attempt" and event.get("score") == 0 for event in events):
        raise ValueError("Expected an attempt with a measured zero score")
    if not any(event["type"] == "engagement" and "score" not in event for event in events):
        raise ValueError("Expected an unscored engagement")
    records_by_identity = {identity(record): record for record in records}
    if len(records_by_identity) != len(records):
        raise ValueError("Duplicate record identity in release fixtures")

    def resolve(reference):
        try:
            return records_by_identity[identity(reference)]
        except KeyError as exc:
            raise ValueError(f"Unresolved fixture reference: {reference}") from exc

    for record in records:
        for reference in record_references(record):
            resolve(reference)

    reference_validator = validators["common", "v2"].evolve(
        schema={"$ref": "https://thia.example/gestalts/common/v2.json#/$defs/recordReference"}
    )
    validate_desktop(records, resolve, reference_validator.validate)

    vectors = read_json(EXAMPLES / "v1" / "identity.json")
    for relation, pairs in vectors.items():
        for left, right in pairs:
            validate(left)
            validate(right)
            if (identity(left) == identity(right)) != (relation == "equal"):
                raise ValueError(f"Expected {relation} identities: {left}, {right}")

    print("Validated all twelve kinds, local references, desktop cases and identity vectors.")


if __name__ == "__main__":
    main()
