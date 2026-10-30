package vn.edu.hcmut.co3103.lms.service.adaptive;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.hcmut.co3103.lms.entity.SkillPrerequisite;
import vn.edu.hcmut.co3103.lms.entity.SkillPrerequisiteId;
import vn.edu.hcmut.co3103.lms.entity.SkillTopic;
import vn.edu.hcmut.co3103.lms.enums.TopicDifficulty;
import vn.edu.hcmut.co3103.lms.repository.adaptive.*;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.ClassAccess;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SkillTopicServiceTest {
    private static final UUID CLASS_ID = UUID.randomUUID();
    private static final UUID TOPIC_ID = UUID.randomUUID();
    private static ValidatorFactory validation;
    private SkillTopicRepository topics;
    private SkillPrerequisiteRepository prerequisites;
    private LessonSkillTopicRepository lessons;
    private ActivitySkillTopicRepository activities;
    private QuestionSkillTopicRepository questions;
    private ClassAccess access;
    private SkillTopicService service;

    @BeforeAll
    static void startValidation() { validation = Validation.buildDefaultValidatorFactory(); }

    @AfterAll
    static void closeValidation() { validation.close(); }

    @BeforeEach
    void setUp() {
        topics = mock(SkillTopicRepository.class);
        prerequisites = mock(SkillPrerequisiteRepository.class);
        lessons = mock(LessonSkillTopicRepository.class);
        activities = mock(ActivitySkillTopicRepository.class);
        questions = mock(QuestionSkillTopicRepository.class);
        access = mock(ClassAccess.class);
        service = new SkillTopicService(topics, prerequisites, lessons, activities, questions,
                access, validation.getValidator());
    }

    @Test
    void createTrimsBeforeUniquenessCheckAndPreservesEnum() {
        when(topics.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        SkillTopic result = service.create(CLASS_ID, "  Algebra  ", null, TopicDifficulty.MEDIUM);
        assertNotNull(result.getSkillTopicId());
        assertEquals(CLASS_ID, result.getClassId());
        assertEquals("Algebra", result.getName());
        assertNull(result.getDescription());
        assertEquals(TopicDifficulty.MEDIUM, result.getDifficultyLevel());
        var order = inOrder(access, topics);
        order.verify(access).lockForWrite(CLASS_ID);
        order.verify(topics).existsByClassIdAndName(CLASS_ID, "Algebra");
        order.verify(topics).saveAndFlush(result);
    }

    @Test
    void rejectsDuplicateNormalizedName() {
        when(topics.existsByClassIdAndName(CLASS_ID, "Algebra")).thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.create(CLASS_ID, " Algebra ", null, null));
        verify(topics, never()).saveAndFlush(any());
    }

    @Test
    void validatesNameUsingExistingEntityConstraintsBeforeWriting() {
        assertThrows(ConstraintViolationException.class,
                () -> service.create(CLASS_ID, null, null, null));
        assertThrows(ConstraintViolationException.class,
                () -> service.create(CLASS_ID, "x".repeat(151), null, null));
        verify(topics, never()).saveAndFlush(any());
    }

    @Test
    void acceptsExactly150CharactersAndNullableDifficulty() {
        when(topics.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        SkillTopic result = service.create(CLASS_ID, " " + "x".repeat(150) + " ", null, null);
        assertEquals(150, result.getName().length());
        assertNull(result.getDifficultyLevel());
    }

    @Test
    void updateExcludesOwnIdAndDoesNotMoveTheTopic() {
        SkillTopic existing = existingTopic();
        when(topics.saveAndFlush(existing)).thenReturn(existing);
        assertSame(existing, service.update(TOPIC_ID, " Updated ", null, TopicDifficulty.HARD));
        assertEquals(TOPIC_ID, existing.getSkillTopicId());
        assertEquals(CLASS_ID, existing.getClassId());
        assertEquals("Updated", existing.getName());
        assertEquals(TopicDifficulty.HARD, existing.getDifficultyLevel());
        verify(topics).existsByClassIdAndNameAndSkillTopicIdNot(CLASS_ID, "Updated", TOPIC_ID);
        var order = inOrder(access, topics);
        order.verify(topics).findClassIdBySkillTopicId(TOPIC_ID);
        order.verify(access).lockForWrite(CLASS_ID);
        order.verify(topics).findById(TOPIC_ID);
    }

    @Test
    void duplicateOrInvalidUpdateDoesNotMutateManagedEntity() {
        SkillTopic existing = existingTopic();
        when(topics.existsByClassIdAndNameAndSkillTopicIdNot(CLASS_ID, "Taken", TOPIC_ID))
                .thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.update(TOPIC_ID, " Taken ", "changed", TopicDifficulty.HARD));
        assertThrows(ConstraintViolationException.class,
                () -> service.update(TOPIC_ID, null, "changed", TopicDifficulty.HARD));
        assertEquals("Original", existing.getName());
        assertEquals("description", existing.getDescription());
        verify(topics, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void rejectsDeletionForEveryKnownReferenceKind(int referenceKind) {
        existingTopic();
        switch (referenceKind) {
            case 0 -> when(prerequisites.findAllById_SkillTopicId(TOPIC_ID)).thenReturn(List.of(
                    new SkillPrerequisite(new SkillPrerequisiteId(TOPIC_ID, UUID.randomUUID()))));
            case 1 -> when(prerequisites.existsById_PrerequisiteSkillTopicId(TOPIC_ID)).thenReturn(true);
            case 2 -> when(lessons.existsById_SkillTopicId(TOPIC_ID)).thenReturn(true);
            case 3 -> when(activities.existsById_SkillTopicId(TOPIC_ID)).thenReturn(true);
            case 4 -> when(questions.existsById_SkillTopicId(TOPIC_ID)).thenReturn(true);
            default -> fail("Unexpected test case");
        }
        assertThrows(IllegalStateException.class, () -> service.delete(TOPIC_ID));
        verify(topics, never()).delete(any());
        verify(prerequisites, never()).deleteAll(anyIterable());
    }

    @Test
    void deletesUnreferencedTopicWithoutDeletingAssociations() {
        SkillTopic existing = existingTopic();
        service.delete(TOPIC_ID);
        verify(topics).delete(existing);
        verify(topics).flush();
        verify(lessons, never()).deleteAll(anyIterable());
        verify(activities, never()).deleteAll(anyIterable());
        verify(questions, never()).deleteAll(anyIterable());
    }

    @Test
    void checksReadAccessForListsAndDetails() {
        SkillTopic existing = existingTopic();
        when(topics.findAllByClassId(CLASS_ID)).thenReturn(List.of(existing));
        assertEquals(List.of(existing), service.listByClass(CLASS_ID));
        assertSame(existing, service.get(TOPIC_ID));
        verify(access, times(2)).requireReadAccess(CLASS_ID);
    }

    @Test
    void missingTopicAndDeniedClassFailWithoutWrites() {
        assertThrows(EntityNotFoundException.class, () -> service.get(TOPIC_ID));
        doThrow(new SecurityException("Denied")).when(access).lockForWrite(CLASS_ID);
        assertThrows(SecurityException.class,
                () -> service.create(CLASS_ID, "Valid", null, null));
        verify(topics, never()).saveAndFlush(any());
    }

    private SkillTopic existingTopic() {
        SkillTopic topic = new SkillTopic(TOPIC_ID, CLASS_ID, "Original", "description", TopicDifficulty.EASY);
        when(topics.findClassIdBySkillTopicId(TOPIC_ID)).thenReturn(Optional.of(CLASS_ID));
        when(topics.findById(TOPIC_ID)).thenReturn(Optional.of(topic));
        return topic;
    }
}
