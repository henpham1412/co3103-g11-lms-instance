package vn.edu.hcmut.co3103.lms.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.edu.hcmut.co3103.lms.entity.Class;

public class ClassUpdateRequest {

    private String title;
    private String description;
    private String prerequisitesDescription;
    private Integer maxCapacity;
    private Class.EnrollmentPolicy enrollmentPolicy;
    private Class.ClassStatus status;

    private boolean titleProvided;
    private boolean descriptionProvided;
    private boolean prerequisitesDescriptionProvided;
    private boolean maxCapacityProvided;
    private boolean enrollmentPolicyProvided;
    private boolean statusProvided;

    @JsonProperty("title")
    public void setTitle(String title) {
        this.titleProvided = true;
        this.title = title;
    }

    @JsonProperty("description")
    public void setDescription(String description) {
        this.descriptionProvided = true;
        this.description = description;
    }

    @JsonProperty("prerequisites_description")
    public void setPrerequisitesDescription(String prerequisitesDescription) {
        this.prerequisitesDescriptionProvided = true;
        this.prerequisitesDescription = prerequisitesDescription;
    }

    @JsonProperty("max_capacity")
    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    public void setMaxCapacity(Integer maxCapacity) {
        this.maxCapacityProvided = true;
        this.maxCapacity = maxCapacity;
    }

    @JsonProperty("enrollment_policy")
    public void setEnrollmentPolicy(Class.EnrollmentPolicy enrollmentPolicy) {
        this.enrollmentPolicyProvided = true;
        this.enrollmentPolicy = enrollmentPolicy;
    }

    @JsonProperty("status")
    public void setStatus(Class.ClassStatus status) {
        this.statusProvided = true;
        this.status = status;
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("Unsupported class field: " + fieldName);
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getPrerequisitesDescription() {
        return prerequisitesDescription;
    }

    public Integer getMaxCapacity() {
        return maxCapacity;
    }

    public Class.EnrollmentPolicy getEnrollmentPolicy() {
        return enrollmentPolicy;
    }

    public Class.ClassStatus getStatus() {
        return status;
    }

    public boolean isTitleProvided() {
        return titleProvided;
    }

    public boolean isDescriptionProvided() {
        return descriptionProvided;
    }

    public boolean isPrerequisitesDescriptionProvided() {
        return prerequisitesDescriptionProvided;
    }

    public boolean isMaxCapacityProvided() {
        return maxCapacityProvided;
    }

    public boolean isEnrollmentPolicyProvided() {
        return enrollmentPolicyProvided;
    }

    public boolean isStatusProvided() {
        return statusProvided;
    }

    public boolean hasAnyField() {
        return titleProvided
                || descriptionProvided
                || prerequisitesDescriptionProvided
                || maxCapacityProvided
                || enrollmentPolicyProvided
                || statusProvided;
    }
}