package vn.edu.hcmut.co3103.lms.service.adaptive;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static vn.edu.hcmut.co3103.lms.service.adaptive.SkillPrerequisiteValidator.validate;
import static vn.edu.hcmut.co3103.lms.service.adaptive.SkillPrerequisiteValidator.validateReplacement;
import vn.edu.hcmut.co3103.lms.service.adaptive.SkillPrerequisiteValidator.PrerequisiteEdge;

class SkillPrerequisiteValidatorTest {

    private static final UUID CLASS_ID = new UUID(0, 100);
    private static final UUID OTHER_CLASS_ID = new UUID(0, 200);
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);
    private static final UUID D = new UUID(0, 4);
    private static final Map<UUID, UUID> TOPICS = Map.of(
            A, CLASS_ID, B, CLASS_ID, C, CLASS_ID, D, CLASS_ID);

    @Test
    void acceptsEmptyGraphAndIsolatedTopics() {
        assertDoesNotThrow(() -> validate(Map.of(), Set.of()));
        assertDoesNotThrow(() -> validate(TOPICS, Set.of()));
    }

    @Test
    void acceptsDiamondWithSharedPrerequisite() {
        assertDoesNotThrow(() -> validate(TOPICS, Set.of(
                edge(A, B), edge(A, C), edge(B, D), edge(C, D))));
    }

    @Test
    void acceptsIndependentGraphsInDifferentClasses() {
        Map<UUID, UUID> topics = Map.of(
                A, CLASS_ID, B, CLASS_ID, C, OTHER_CLASS_ID, D, OTHER_CLASS_ID);
        assertDoesNotThrow(() -> validate(topics, Set.of(edge(A, B), edge(C, D))));
    }

    @Test
    void rejectsSelfLink() {
        assertThrows(IllegalArgumentException.class, () -> validate(TOPICS, Set.of(edge(A, A))));
    }

    @Test
    void rejectsCrossClassEdge() {
        Map<UUID, UUID> topics = Map.of(A, CLASS_ID, B, OTHER_CLASS_ID);
        assertThrows(IllegalArgumentException.class, () -> validate(topics, Set.of(edge(A, B))));
    }

    @Test
    void rejectsUnknownTargetOrPrerequisite() {
        assertThrows(IllegalArgumentException.class,
                () -> validate(Map.of(A, CLASS_ID), Set.of(edge(A, B))));
        assertThrows(IllegalArgumentException.class,
                () -> validate(Map.of(B, CLASS_ID), Set.of(edge(A, B))));
    }

    @Test
    void rejectsDirectCycle() {
        assertThrows(IllegalArgumentException.class,
                () -> validate(TOPICS, Set.of(edge(A, B), edge(B, A))));
    }

    @Test
    void rejectsIndirectCycle() {
        assertThrows(IllegalArgumentException.class,
                () -> validate(TOPICS, Set.of(edge(A, B), edge(B, C), edge(C, A))));
    }

    @Test
    void rejectsCycleInDisconnectedComponent() {
        assertThrows(IllegalArgumentException.class,
                () -> validate(TOPICS, Set.of(edge(A, B), edge(C, D), edge(D, C))));
    }

    @Test
    void replacementRemovesOldEdgesBeforeValidation() {
        Set<PrerequisiteEdge> edges = Set.of(edge(A, B), edge(B, A));
        // The old graph is cyclic, but replacing A's outgoing edges repairs it.
        assertDoesNotThrow(() -> validateReplacement(TOPICS, edges, A, Set.of(C)));
    }

    @Test
    void emptyReplacementRemovesPrerequisitesButRetainsIncomingEdges() {
        assertDoesNotThrow(() -> validateReplacement(
                TOPICS, Set.of(edge(A, B), edge(B, A)), A, Set.of()));
        // B -> A must remain, so a replacement A -> B must still be rejected.
        assertThrows(IllegalArgumentException.class, () -> validateReplacement(
                TOPICS, Set.of(edge(B, A)), A, Set.of(B)));
    }

    @Test
    void rejectsReplacementCreatingIndirectCycle() {
        assertThrows(IllegalArgumentException.class, () -> validateReplacement(
                TOPICS, Set.of(edge(A, B), edge(B, C)), C, Set.of(A)));
    }

    @Test
    void replacementDoesNotRemoveOtherTargetsEdges() {
        assertThrows(IllegalArgumentException.class, () -> validateReplacement(
                TOPICS, Set.of(edge(B, C), edge(C, B)), A, Set.of(D)));
    }

    @Test
    void rejectsInvalidReplacementReferences() {
        assertThrows(IllegalArgumentException.class,
                () -> validateReplacement(TOPICS, Set.of(), A, Set.of(A)));
        assertThrows(IllegalArgumentException.class,
                () -> validateReplacement(Map.of(A, CLASS_ID), Set.of(), A, Set.of(B)));
        assertThrows(IllegalArgumentException.class,
                () -> validateReplacement(Map.of(A, CLASS_ID), Set.of(), B, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> validateReplacement(
                Map.of(A, CLASS_ID, B, OTHER_CLASS_ID), Set.of(), A, Set.of(B)));
    }

    @Test
    void leavesInputsUnchangedOnSuccessAndFailure() {
        Map<UUID, UUID> topics = new HashMap<>(TOPICS);
        Set<PrerequisiteEdge> edges = new HashSet<>(Set.of(edge(A, B)));
        Set<UUID> prerequisites = new HashSet<>(Set.of(C));
        validateReplacement(topics, edges, A, prerequisites);
        assertThrows(IllegalArgumentException.class,
                () -> validateReplacement(topics, edges, B, Set.of(A)));
        assertEquals(TOPICS, topics);
        assertEquals(Set.of(edge(A, B)), edges);
        assertEquals(Set.of(C), prerequisites);
    }

    @Test
    void rejectsNullIdentifiersAndClassIds() {
        assertThrows(NullPointerException.class, () -> edge(null, B));
        assertThrows(NullPointerException.class, () -> edge(A, null));
        Map<UUID, UUID> topics = new HashMap<>();
        topics.put(A, null);
        assertThrows(NullPointerException.class, () -> validate(topics, Set.of()));
    }

    private static PrerequisiteEdge edge(UUID target, UUID prerequisite) {
        return new PrerequisiteEdge(target, prerequisite);
    }
}
