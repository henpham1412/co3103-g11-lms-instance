package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Getter
@Setter
@Entity
@Table(name = "attempt_answer_option")
public class AttemptAnswerOption {
    @EmbeddedId
    private AttemptAnswerOptionId id;

    @MapsId("answerId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "answer_id", nullable = false)
    private AttemptAnswer answer;

    @MapsId("optionId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "option_id", nullable = false)
    private QuestionOption option;


}