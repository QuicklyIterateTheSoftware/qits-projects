package eu.wohlben.qits.projects.dto;

/**
 * One participant of a release request, as the API answers it.
 *
 * <p>Two kinds travel through the same shape on purpose, and {@code implicit} is what tells them
 * apart for a caller:
 *
 * <ul>
 *   <li>{@code kind=BRANCH}, {@code implicit=false} — a branch somebody put on the request. {@code
 *       main} is on every request (a create implies it) and is caller-visible like any other, so
 *       "what is in this release" needs no special case.
 *   <li>{@code kind=RELEASED_TAG}, {@code implicit=true} — a release of this repository that has not
 *       reached {@code main} yet. Derived, never caller-managed: it joins every open request of the
 *       repository the moment a sibling releases and leaves them all the moment that tag is merged.
 *       A caller cannot add or remove one, and an API that let it would let somebody release a step
 *       backwards from what is already shipping.
 * </ul>
 *
 * <p>{@code ref} is the fully qualified name the git host is given ({@code refs/heads/main},
 * {@code refs/tags/2026.903.1}); {@code name} is the same thing as a person spells it.
 */
public record ReleaseRequestSourceDto(String kind, String name, String ref, boolean implicit) {}
