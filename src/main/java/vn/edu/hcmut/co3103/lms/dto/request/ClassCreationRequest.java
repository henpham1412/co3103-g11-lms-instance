package vn.edu.hcmut.co3103.lms.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.edu.hcmut.co3103.lms.entity.Class;

public class ClassCreationRequest {

    @JsonProperty("title")
    @NotBlank
    @Size(max = 150)
    private String title;

    @JsonProperty("class_code")
    @NotBlank
    @Size(min = 3, max = 30)
    @Pattern(regexp = "^[A-Za-z0-9_-]+$")
    private String classCode;

    @JsonProperty("description")
    @Size(max = 2000)
    private String description;

    @JsonProperty("prerequisites_description")
    private String prerequisitesDescription;

    @JsonProperty("max_capacity")
    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    @Positive
    private Integer maxCapacity;

    @JsonProperty("enrollment_policy")
    @NotNull
    private Class.EnrollmentPolicy enrollmentPolicy;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getClassCode() {
        return classCode;
    }

    public void setClassCode(String classCode) {
        this.classCode = classCode;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPrerequisitesDescription() {
        return prerequisitesDescription;
    }

    public void setPrerequisitesDescription(String prerequisitesDescription) {
        this.prerequisitesDescription = prerequisitesDescription;
    }

    public Integer getMaxCapacity() {
        return maxCapacity;
    }

    public void setMaxCapacity(Integer maxCapacity) {
        this.maxCapacity = maxCapacity;
    }

    public Class.EnrollmentPolicy getEnrollmentPolicy() {
        return enrollmentPolicy;
    }

    public void setEnrollmentPolicy(Class.EnrollmentPolicy enrollmentPolicy) {
        this.enrollmentPolicy = enrollmentPolicy;
    }
}