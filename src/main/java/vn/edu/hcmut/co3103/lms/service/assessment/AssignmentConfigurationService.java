package vn.edu.hcmut.co3103.lms.service.assessment;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import vn.edu.hcmut.co3103.lms.dto.request.AssignmentConfigurationRequest;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Assignment;
import vn.edu.hcmut.co3103.lms.repository.assessment.AssignmentRepository;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Assessment configuration inside the canonical content transaction; no competing placement route. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AssignmentConfigurationService {
    private static final Set<String> FIELDS = Set.of("opening_time", "deadline", "allow_late", "allowed_file_types", "max_file_size_mb");
    private final AssignmentRepository assignments;
    private final ObjectMapper mapper;
    public AssignmentConfigurationService(AssignmentRepository assignments, ObjectMapper mapper) {
        this.assignments = assignments; this.mapper = mapper;
    }
    public void create(Activity activity, Object configuration) {
        if (!(configuration instanceof Map<?, ?> fields)) throw invalid();
        var request = parse(fields);
        validate(request, true);
        assignments.saveAndFlush(new Assignment(activity.getActivityId(), request.openingTime(), request.deadline(),
                Boolean.TRUE.equals(request.allowLate()), join(request.allowedFileTypes()), request.maxFileSizeMb()));
    }
    public void patch(Assignment assignment, Map<String, Object> patch) {
        if (patch == null || patch.isEmpty()) throw invalid();
        checkFields(patch);
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("opening_time", assignment.getOpeningTime()); merged.put("deadline", assignment.getDeadline());
        merged.put("allow_late", assignment.isAllowLate()); merged.put("max_file_size_mb", assignment.getMaxFileSizeMb());
        merged.put("allowed_file_types", assignment.getAllowedFileTypes() == null ? null : List.of(assignment.getAllowedFileTypes().split(",")));
        // Validate supplied JSON types before merging; dates already held in the entity are trusted values.
        parse(patch);
        merged.putAll(patch);
        AssignmentConfigurationRequest request;
        try { request = mapper.convertValue(merged, AssignmentConfigurationRequest.class); }
        catch (Exception malformed) { throw invalid(); }
        validate(request, patch.containsKey("deadline"));
        assignment.setOpeningTime(request.openingTime()); assignment.setDeadline(request.deadline());
        assignment.setAllowLate(Boolean.TRUE.equals(request.allowLate()));
        assignment.setAllowedFileTypes(join(request.allowedFileTypes())); assignment.setMaxFileSizeMb(request.maxFileSizeMb());
    }
    private AssignmentConfigurationRequest parse(Map<?, ?> fields) {
        checkFields(fields);
        for (var entry : fields.entrySet()) {
            Object value = entry.getValue();
            boolean valid = switch (entry.getKey().toString()) {
                case "opening_time", "deadline" -> value == null || value instanceof String;
                case "allow_late" -> value instanceof Boolean;
                case "max_file_size_mb" -> value == null || value instanceof Integer;
                case "allowed_file_types" -> value == null || value instanceof List<?> values && values.stream().allMatch(v -> v instanceof String);
                default -> false;
            };
            if (!valid) throw invalid();
        }
        try { return mapper.convertValue(fields, AssignmentConfigurationRequest.class); }
        catch (Exception malformed) { throw invalid(); }
    }
    private void checkFields(Map<?, ?> fields) {
        if (!FIELDS.containsAll(fields.keySet())) throw invalid();
    }
    private void validate(AssignmentConfigurationRequest request, boolean futureDeadline) {
        if (request.deadline() == null || futureDeadline && !request.deadline().isAfter(OffsetDateTime.now())
                || request.openingTime() != null && !request.openingTime().isBefore(request.deadline())
                || request.maxFileSizeMb() != null && request.maxFileSizeMb() <= 0) throw invalid();
        try {
            if (request.allowedFileTypes() != null) request.allowedFileTypes().forEach(AssignmentFileTypes::mime);
            String types = join(request.allowedFileTypes());
            if (types != null && types.length() > 1000) throw invalid();
            AssignmentFileTypes.allowed(types);
        } catch (IllegalArgumentException invalidType) { throw invalid(); }
    }
    private String join(List<String> types) {
        return types == null || types.isEmpty() ? null : String.join(",", types);
    }
    private ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"); }
}
