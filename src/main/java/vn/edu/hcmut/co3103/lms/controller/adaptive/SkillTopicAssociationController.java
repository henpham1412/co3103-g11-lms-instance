package vn.edu.hcmut.co3103.lms.controller.adaptive;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.hcmut.co3103.lms.dto.request.adaptive.ReplaceSkillTopicsRequest;
import vn.edu.hcmut.co3103.lms.dto.response.adaptive.SkillTopicResponse;
import vn.edu.hcmut.co3103.lms.dto.response.adaptive.TopicAssociationChangeResponse;
import vn.edu.hcmut.co3103.lms.mapper.adaptive.SkillTopicApiMapper;
import vn.edu.hcmut.co3103.lms.service.adaptive.SkillTopicAssociationService;

import java.util.List;
import java.util.UUID;

/** Question writes remain exclusively in the LMS-5 workflow. */
@RestController
@RequestMapping("/api/v1")
public class SkillTopicAssociationController {
    private final SkillTopicAssociationService service;

    public SkillTopicAssociationController(SkillTopicAssociationService service) {
        this.service = service;
    }

    @GetMapping("/lessons/{lessonId}/skill-topics")
    public List<SkillTopicResponse> getLessonTopics(@PathVariable("lessonId") UUID lessonId) {
        return service.getLessonTopics(lessonId).stream().map(SkillTopicApiMapper::toResponse).toList();
    }

    @PutMapping("/lessons/{lessonId}/skill-topics")
    public TopicAssociationChangeResponse replaceLessonTopics(@PathVariable("lessonId") UUID lessonId,
                                                              @Valid @RequestBody ReplaceSkillTopicsRequest request) {
        return SkillTopicApiMapper.toResponse(service.replaceLessonTopics(lessonId, request.skillTopicIds()));
    }

    @GetMapping("/activities/{activityId}/skill-topics")
    public List<SkillTopicResponse> getActivityTopics(@PathVariable("activityId") UUID activityId) {
        return service.getActivityTopics(activityId).stream().map(SkillTopicApiMapper::toResponse).toList();
    }

    @PutMapping("/activities/{activityId}/skill-topics")
    public TopicAssociationChangeResponse replaceAssignmentTopics(@PathVariable("activityId") UUID activityId,
                                                                  @Valid @RequestBody ReplaceSkillTopicsRequest request) {
        // The service rejects Quiz writes; authorization and type checks stay there.
        return SkillTopicApiMapper.toResponse(service.replaceActivityTopics(activityId, request.skillTopicIds()));
    }
}
