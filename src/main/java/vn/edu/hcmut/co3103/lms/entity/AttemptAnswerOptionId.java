package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;

@Getter
@Setter
@EqualsAndHashCode
@Embeddable
public class AttemptAnswerOptionId implements Serializable {
    @Serial
    private static final long serialVersionUID = 8879447185182317976L;
    @NotNull
    @Column(name = "answer_id", nullable = false)
    private Integer answerId;

    @NotNull
    @Column(name = "option_id", nullable = false)
    private Integer optionId;


}