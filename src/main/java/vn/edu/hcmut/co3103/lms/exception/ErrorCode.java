package vn.edu.hcmut.co3103.lms.exception;

import org.springframework.http.HttpStatus;

/** Stable public codes and safe messages; services do not select HTTP responses. */
public enum ErrorCode {
    SKILL_TOPIC_NOT_FOUND("Skill topic not found", HttpStatus.NOT_FOUND),
    CLASS_NOT_FOUND("Class not found", HttpStatus.NOT_FOUND),
    CLASS_ACCESS_DENIED("Access to this class is denied", HttpStatus.FORBIDDEN),
    SKILL_TOPIC_NAME_DUPLICATE("A topic with this name already exists in the class", HttpStatus.CONFLICT),
    INVALID_SKILL_TOPIC("Skill topic data is invalid", HttpStatus.BAD_REQUEST),
    VALIDATION_ERROR("The request contains invalid or unsupported values.", HttpStatus.BAD_REQUEST),
    SKILL_TOPIC_REFERENCED("Cannot delete a referenced skill topic", HttpStatus.CONFLICT),
    PREREQUISITE_SELF_REFERENCE("A topic cannot be its own prerequisite", HttpStatus.BAD_REQUEST),
    PREREQUISITE_CLASS_MISMATCH("Prerequisite topics must belong to the same class", HttpStatus.BAD_REQUEST),
    PREREQUISITE_CYCLE("Prerequisite graph must be acyclic", HttpStatus.CONFLICT),
    LEARNING_RESOURCE_NOT_FOUND("Learning resource not found", HttpStatus.NOT_FOUND),
    RESOURCE_CLASS_MISMATCH("Resource and topics must belong to the same class", HttpStatus.BAD_REQUEST),
    INVALID_TOPIC_ASSOCIATION("Topic association is invalid", HttpStatus.BAD_REQUEST),
    QUIZ_TOPICS_DERIVED("Quiz Activity topics are derived from its Questions", HttpStatus.CONFLICT),
    RESOURCE_STATE_CONFLICT("Resource state changed; retry the operation", HttpStatus.CONFLICT),
    INVALID_REQUEST("Request data is invalid", HttpStatus.BAD_REQUEST),
    RESOURCE_NOT_FOUND("Resource not found", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED("HTTP method is not allowed for this resource", HttpStatus.METHOD_NOT_ALLOWED),
    NOT_ACCEPTABLE("Requested response media type is not supported", HttpStatus.NOT_ACCEPTABLE),
    UNSUPPORTED_MEDIA_TYPE("Request media type is not supported", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    DATA_CONFLICT("Operation conflicts with stored data", HttpStatus.CONFLICT),
    FORBIDDEN("Access to this resource is denied", HttpStatus.FORBIDDEN),
    CLASS_ARCHIVED("The class is archived and no longer accepts new activity.", HttpStatus.CONFLICT),
    DISCUSSION_NOT_FOUND("Discussion not found", HttpStatus.NOT_FOUND),
    DISCUSSION_HAS_REPLIES("Cannot delete a discussion that still has replies", HttpStatus.CONFLICT),
    INTERNAL_ERROR("An unexpected error occurred", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String message;
    private final HttpStatus status;

    ErrorCode(String message, HttpStatus status) {
        this.message = message;
        this.status = status;
    }

    public String getMessage() { return message; }
    public HttpStatus getStatus() { return status; }
}
