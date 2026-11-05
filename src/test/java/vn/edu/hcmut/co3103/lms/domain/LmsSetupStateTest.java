package vn.edu.hcmut.co3103.lms.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LmsSetupStateTest {

    @Test
    void rejectsNullIdentityBeforeMutatingState() {
        var state = new LmsSetupState();
        assertThatThrownBy(() -> state.complete(null)).isInstanceOf(NullPointerException.class);
        assertThat(state.isCompleted()).isFalse();
        assertThat(state.getCompletedAt()).isNull();
        assertThat(state.getBootstrappedBy()).isNull();
    }

    @Test
    void completesOnceWithoutAllowingIdentityOrTimestampToBeRewritten() {
        var state = new LmsSetupState();
        UUID id = UUID.randomUUID();
        Instant before = Instant.now();
        state.complete(id);
        Instant completedAt = state.getCompletedAt();
        assertThat(state.isCompleted()).isTrue();
        assertThat(completedAt).isBetween(before, Instant.now());
        assertThat(state.getBootstrappedBy()).isEqualTo(id);

        for (UUID retry : new UUID[] {id, UUID.randomUUID(), null}) {
            assertThatThrownBy(() -> state.complete(retry)).isInstanceOf(IllegalStateException.class);
            assertThat(state.isCompleted()).isTrue();
            assertThat(state.getCompletedAt()).isEqualTo(completedAt);
            assertThat(state.getBootstrappedBy()).isEqualTo(id);
        }
    }
}
