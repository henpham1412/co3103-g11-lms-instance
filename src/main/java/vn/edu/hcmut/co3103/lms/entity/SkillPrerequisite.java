package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "skill_prerequisites")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SkillPrerequisite {

    @EmbeddedId
    private SkillPrerequisiteId id;

    public SkillPrerequisite(SkillPrerequisiteId id) {
        this.id = id;
    }
}