# `images/` — build definitions for published OCI images

Archetype: **`IMAGE`**

Dockerfiles and their build context: the base images and toolchain images the rest of the project
builds on. What ships is the published image, not the source — every consumer references it by tag.

Put the definitions directly here to start with (`images/builder/`). When one earns its own
repository, extracting this directory produces a repository with archetype `IMAGE`, which is
re-attached as a submodule at this same path.
