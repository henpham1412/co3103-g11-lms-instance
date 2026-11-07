package vn.edu.hcmut.co3103.lms.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

public record OrderUpdateRequest(@JsonProperty("chapter_ids") List<UUID> chapterIds,
                                @JsonProperty("lesson_ids") List<UUID> lessonIds) { }
