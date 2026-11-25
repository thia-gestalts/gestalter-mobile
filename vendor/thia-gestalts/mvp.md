# First usable schema release

Ship versioned files such as `topic/v1.json`, one directory per kind. Include the following vocabulary.

| Kind | Meaning |
| --- | --- |
| note | Learner-authored text with optional attachments and relationships. |
| annotation | Authored text attached to an identified target, with an optional locator. |
| topic | Named subject for connecting activities and records. |
| fact | Declarative knowledge a learner can practise. |
| skill | Procedural or perceptual ability a learner can practise. |
| question | Research or learner question that an activity or artifact can open or answer. |
| artifact | Named work or object a learner can refer to, including a book. |
| entity | Referenced person, institution or other identifiable actor. |
| claim | An assertion that can carry attribution and supporting references. |
| learning-event | One observed interaction, including neutral engagement or an attempt, its time, origin and targets. |
| flashcard | A front/back exercise that can target facts. |
| task | A concrete instruction with a checkable outcome that can exercise a skill. |

A client can create a note offline, attach an image by reference, record a practice attempt and resolve its targets offline. Two imports of the same named artifact produce one shared artifact while retaining distinct academic records. A zero score and unscored engagement survive a round trip as different observations. No curated topic registry is needed.
