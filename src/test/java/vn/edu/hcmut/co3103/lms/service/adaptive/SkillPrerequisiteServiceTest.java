package vn.edu.hcmut.co3103.lms.service.adaptive;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.hcmut.co3103.lms.entity.SkillPrerequisite;
import vn.edu.hcmut.co3103.lms.entity.SkillPrerequisiteId;
import vn.edu.hcmut.co3103.lms.entity.SkillTopic;
import vn.edu.hcmut.co3103.lms.repository.adaptive.SkillPrerequisiteRepository;
import vn.edu.hcmut.co3103.lms.repository.adaptive.SkillTopicRepository;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.ClassAccess;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SkillPrerequisiteServiceTest {
    private static final UUID CLASS_ID = UUID.randomUUID();
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private SkillTopicRepository topics;
    private SkillPrerequisiteRepository prerequisites;
    private ClassAccess access;
    private SkillPrerequisiteService service;

    @BeforeEach
    void setUp() {
        topics = mock(SkillTopicRepository.class);
        prerequisites = mock(SkillPrerequisiteRepository.class);
        access = mock(ClassAccess.class);
        service = new SkillPrerequisiteService(topics, prerequisites, access);
        Map<UUID, SkillTopic> all = Map.of(A, topic(A), B, topic(B), C, topic(C));
        when(topics.findClassIdBySkillTopicId(any())).thenReturn(Optional.of(CLASS_ID));
        when(topics.findById(any())).thenAnswer(call -> Optional.ofNullable(all.get(call.getArgument(0))));
        when(topics.findAllByClassId(CLASS_ID)).thenReturn(List.copyOf(all.values()));
        when(topics.findAllById(any())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return ids.stream().filter(all::containsKey).map(all::get).toList();
        });
    }

    @Test
    void replacesOnlyOutgoingEdgesAfterClassLock() {
        SkillPrerequisite old = edge(A, B);
        SkillPrerequisite other = edge(B, C);
        when(prerequisites.findAllById_SkillTopicIdIn(any())).thenReturn(List.of(old, other));
        service.replace(A, List.of(C));
        verify(prerequisites).deleteAll(List.of(old));
        verify(prerequisites).saveAll(argThat(links -> {
            List<SkillPrerequisite> list = (List<SkillPrerequisite>) links;
            return list.size() == 1 && list.getFirst().getId().equals(new SkillPrerequisiteId(A, C));
        }));
        var order = inOrder(access, topics, prerequisites);
        order.verify(access).lockForWrite(CLASS_ID);
        order.verify(topics).findById(A);
        order.verify(topics).findAllById(Set.of(C));
        order.verify(topics).findAllByClassId(CLASS_ID);
        order.verify(prerequisites).findAllById_SkillTopicIdIn(any());
    }

    @Test
    void emptyReplacementClearsOutgoingAndKeepsIncomingEdges() {
        SkillPrerequisite outgoing = edge(A, B);
        when(prerequisites.findAllById_SkillTopicIdIn(any())).thenReturn(List.of(outgoing, edge(C, A)));
        service.replace(A, List.of());
        verify(prerequisites).deleteAll(List.of(outgoing));
        verify(prerequisites).saveAll(List.of());
    }

    @Test
    void removesOldEdgesBeforeValidatingResultingGraph() {
        when(prerequisites.findAllById_SkillTopicIdIn(any())).thenReturn(List.of(edge(A, B), edge(B, A)));
        assertDoesNotThrow(() -> service.replace(A, List.of(C)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void rejectsSelfDirectAndIndirectCyclesBeforeAnyWrite(int kind) {
        List<SkillPrerequisite> existing = switch (kind) {
            case 0 -> List.of();
            case 1 -> List.of(edge(B, A));
            default -> List.of(edge(B, C), edge(C, A));
        };
        when(prerequisites.findAllById_SkillTopicIdIn(any())).thenReturn(existing);
        assertThrows(IllegalArgumentException.class,
                () -> service.replace(A, List.of(kind == 0 ? A : B)));
        verify(prerequisites, never()).deleteAll(anyIterable());
        verify(prerequisites, never()).saveAll(anyIterable());
    }

    @Test
    void rejectsForeignClassAndMissingTopicsBeforeAnyWrite() {
        when(topics.findAllById(Set.of(B))).thenReturn(List.of(
                new SkillTopic(B, UUID.randomUUID(), "Foreign", null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.replace(A, List.of(B)));
        assertThrows(EntityNotFoundException.class, () -> service.replace(A, List.of(UUID.randomUUID())));
        verify(prerequisites, never()).deleteAll(anyIterable());
    }

    @Test
    void rejectsDuplicateAndNullIdsBeforeLocking() {
        assertThrows(IllegalArgumentException.class, () -> service.replace(A, List.of(B, B)));
        assertThrows(IllegalArgumentException.class,
                () -> service.replace(A, java.util.Arrays.asList(B, null)));
        verifyNoInteractions(access);
    }

    @Test
    void unchangedLinksAreNotDeletedOrRecreated() {
        when(prerequisites.findAllById_SkillTopicIdIn(any())).thenReturn(List.of(edge(A, B)));
        service.replace(A, List.of(B));
        verify(prerequisites).deleteAll(List.of());
        verify(prerequisites).saveAll(List.of());
    }

    @Test
    void readsRequireClassAccessAndWriteDenialPreventsMutation() {
        service.list(A);
        verify(access).requireReadAccess(CLASS_ID);
        doThrow(new SecurityException("Denied")).when(access).lockForWrite(CLASS_ID);
        assertThrows(SecurityException.class, () -> service.replace(A, List.of(B)));
        verify(prerequisites, never()).deleteAll(anyIterable());
    }

    private static SkillTopic topic(UUID id) { return new SkillTopic(id, CLASS_ID, id.toString(), null, null); }
    private static SkillPrerequisite edge(UUID target, UUID required) {
        return new SkillPrerequisite(new SkillPrerequisiteId(target, required));
    }
}
