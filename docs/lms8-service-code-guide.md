# LMS-8: Command, Service và xử lý lỗi

## A. Luồng hoạt động

Task này chưa thêm Controller, Request DTO, Response DTO hay feature API. Sau này
Controller chuyển input transport thành Command nghiệp vụ, rồi gọi Spring proxy
của Service. Resource ID vẫn là tham số riêng, không nằm trong Command.

Luồng thành công: caller -> Command -> Service -> kiểm tra quyền/class lock qua
adapter thật -> Entity normalization/validation -> Repository -> flush -> commit
transaction -> trả Entity hoặc kết quả nghiệp vụ. Các method đọc dùng transaction
read-only. Không có HTTP status, ResponseEntity hay JSON trong Service.

Luồng lỗi nghiệp vụ: Service/validator/adapter ném AppException(ErrorCode) -> Spring
rollback transaction ghi -> nếu lời gọi đến từ MVC, GlobalExceptionHandler chọn
status/message từ ErrorCode và trả `{code,message,details}` theo DOC-05. Nếu caller
không phải MVC thì exception vẫn được trả về caller; Service không tạo HTTP response.

Lỗi database được phân loại riêng thành conflict; lỗi ngoài dự kiến được log đầy đủ
ở server và trả 500 với thông báo cố định, không trả exception message, SQL hoặc
stack trace cho client. details hiện là danh sách rỗng, không chứa rejected values.
Security filter nằm ngoài MVC advice; task này không tuyên bố thay đổi lỗi JWT/filter.

## B–C. Class và method

Các đường dẫn Java dưới đây tính từ `src/main/java/vn/edu/hcmut/co3103/lms/`.
Constructor Injection và dependency final được giữ nguyên; constructor chỉ nhận
collaborators và không tự tạo implementation upstream.

### `service/adaptive/command/CreateSkillTopicCommand.java`

Record bất biến gồm name, description, difficulty, dùng làm input nghiệp vụ của
`create(classId, command)`. Constructor record gom dữ liệu liên quan, không tự trim
hay lặp lại validation Entity; không chứa resource ID hoặc annotation HTTP.

### `service/adaptive/command/UpdateSkillTopicCommand.java`

Record riêng cho `update(topicId, command)` để phân biệt ý định cập nhật với tạo mới.
Update thay toàn bộ metadata cho phép sửa: description null nghĩa là xóa mô tả;
difficulty bắt buộc. Đây không phải PATCH request DTO. Không thêm Command cho các
method chỉ nhận một ID hoặc một tập topic IDs vì chưa có lợi ích tương ứng.

### `entity/SkillTopic.java`

Entity giữ normalization tại `setName`/callback trước persist/update; các method này
không đổi thuật toán. Annotation mới là `@NotBlank` cho name và `@NotNull` cùng
`nullable=false` cho difficulty. `@Size(max=150)` và Enum converter 1/3/5 được giữ.
description vẫn nullable. Không đổi constructor, auditing hoặc ID/class ownership.

### `service/adaptive/SkillTopicService.java`

CRUD topic, phụ thuộc Repository, ClassAccess và Jakarta Validator. Service không
import transport DTO hoặc HttpStatus. Read-only là mặc định; create/update/delete
có transaction ghi REQUIRED khi được gọi qua Spring proxy.

| Method | Input -> output; xử lý | Lỗi chính |
|---|---|---|
| `create` | classId + CreateSkillTopicCommand -> SkillTopic; kiểm tra ID/Command, yêu cầu class lock, tạo Entity với UUID, dùng Entity để trim/validate, kiểm tra tên trong Class rồi saveAndFlush | INVALID_REQUEST, INVALID_SKILL_TOPIC, SKILL_TOPIC_NAME_DUPLICATE; lỗi Class từ adapter |
| `update` | topicId + UpdateSkillTopicCommand -> SkillTopic; lock topic, tạo candidate để validate trước khi sửa managed Entity, kiểm tra trùng tên loại trừ chính ID, gọi setter tường minh và saveAndFlush | INVALID_SKILL_TOPIC, SKILL_TOPIC_NOT_FOUND, SKILL_TOPIC_NAME_DUPLICATE; lỗi Class từ adapter |
| `listByClass` | classId -> List SkillTopic; validate ID, requireReadAccess, query theo Class | INVALID_REQUEST; CLASS_NOT_FOUND/CLASS_ACCESS_DENIED từ adapter |
| `get` | topicId -> SkillTopic; tìm topic rồi yêu cầu quyền đọc Class | INVALID_REQUEST, SKILL_TOPIC_NOT_FOUND; lỗi quyền từ adapter |
| `delete` | topicId -> void; lock, kiểm tra cả hai hướng prerequisite và ba loại mapping; chỉ delete/flush nếu không tham chiếu | SKILL_TOPIC_NOT_FOUND, SKILL_TOPIC_REFERENCED; restrictive FK vẫn bảo vệ tham chiếu tương lai |
| `lockTopic` | topicId -> SkillTopic; lấy classId bằng scalar projection, lấy class lock rồi mới tải managed Entity | INVALID_REQUEST, SKILL_TOPIC_NOT_FOUND và lỗi adapter; dùng transaction của caller |
| `validate` | SkillTopic -> void; chạy Jakarta Validator theo annotation trên Entity | INVALID_SKILL_TOPIC nếu có violation; không lặp lại quy tắc name bằng validation riêng |

### `service/adaptive/SkillTopicChecks.java`

Helper package-private để các Service dùng chung validation tham chiếu. Không phải
Spring bean; không mở transaction riêng, không tự triển khai hierarchy/authorization.

| Method | Input -> output; xử lý/lỗi |
|---|---|
| `requireId` | UUID -> void; null tạo INVALID_REQUEST |
| `requireTopic` | repository + UUID -> SkillTopic; validate ID, findById; không có tạo SKILL_TOPIC_NOT_FOUND |
| `requireClassId` | repository + topic UUID -> class UUID; dùng scalar projection; không có tạo SKILL_TOPIC_NOT_FOUND |
| `uniqueIds` | Collection UUID -> immutable Set; null collection, null phần tử hoặc ID trùng tạo INVALID_TOPIC_ASSOCIATION |
| `requireTopicsInClass` (3 tham số) | repository + IDs + classId -> List SkillTopic; dùng overload với RESOURCE_CLASS_MISMATCH |
| `requireTopicsInClass` (4 tham số) | thêm mismatchCode; tập rỗng trả List rỗng, kiểm tra tồn tại toàn bộ rồi so classId; thiếu tạo SKILL_TOPIC_NOT_FOUND, khác Class tạo mismatchCode |

### `service/adaptive/SkillPrerequisiteValidator.java`

Giữ nguyên hướng cạnh target -> prerequisite và thuật toán Kahn; chỉ chuẩn hóa
exception thành AppException. Validator không query database hoặc tự mở transaction.

| Method | Input -> output; xử lý/lỗi |
|---|---|
| `validate` | map topic->class và tập cạnh -> void; kiểm tra tồn tại, self-link, cùng Class, rồi kiểm tra toàn graph có chu trình | SKILL_TOPIC_NOT_FOUND, PREREQUISITE_SELF_REFERENCE, PREREQUISITE_CLASS_MISMATCH, PREREQUISITE_CYCLE |
| `validateReplacement` | map, graph cũ, target, tập prerequisite mới -> void; bỏ outgoing cũ của target trong bản sao, thêm tập mới rồi gọi validate; không sửa input | Các lỗi tương ứng từ requireClassId/validate |
| `requireClassId` | map + topicId -> classId; thiếu topic tạo SKILL_TOPIC_NOT_FOUND | Helper nội bộ |

Null ở record cạnh hoặc null cấu trúc graph vẫn là lỗi lập trình của API nội bộ;
Service kiểm tra input nghiệp vụ trước đó. Lỗi lập trình không bị tự động đổi thành
400. Không tạo thuật toán cycle thứ hai trong Service.

### `service/adaptive/SkillPrerequisiteService.java`

Class này giữ nguyên transaction và trình tự lấy lock từ Step 4.

| Method | Input -> output; xử lý/lỗi | Transaction |
|---|---|---|
| `list` | topicId -> danh sách cạnh; yêu cầu topic tồn tại và quyền đọc Class | read-only |
| `replace` | topicId + Collection prerequisite IDs -> void; kiểm tra IDs, lấy class lock, kiểm tra toàn bộ topic mới cùng Class, đọc graph Class, gọi validator; chỉ xóa outgoing lỗi thời và thêm cạnh mới | REQUIRED; lỗi AppException hoặc persistence rollback; IDs lỗi INVALID_TOPIC_ASSOCIATION, khác Class PREREQUISITE_CLASS_MISMATCH, graph lỗi theo validator |

Tập rỗng xóa outgoing của target; không xóa incoming hoặc cạnh của topic khác.

### `service/adaptive/SkillTopicAssociationService.java`

Class này giữ canonical junction entities, validation trước ghi và chính sách Quiz
union. Không tạo Question CRUD thay thế LMS-5. Null resource ID giờ tạo INVALID_REQUEST;
lỗi tham chiếu/graph không được báo bằng IllegalArgumentException tùy tiện.

| Method | Input -> output; xử lý/lỗi | Transaction |
|---|---|---|
| `getLessonTopics` | lessonId -> List topic; resolve Class, kiểm tra quyền, đọc mapping và kiểm tra topic cùng Class | read-only |
| `replaceLessonTopics` | lessonId + IDs -> TopicChange; validate IDs, lock, kiểm tra lại scope, validate toàn bộ topic rồi cập nhật delta/flush | REQUIRED |
| `getActivityTopics` | activityId -> List topic; Assignment đọc mapping, Quiz tính hợp từ Question mappings | read-only |
| `replaceActivityTopics` | activityId + IDs -> TopicChange; lock, kiểm tra scope/lifecycle qua adapter, chỉ cho Assignment, validate rồi ghi delta | REQUIRED; Quiz tạo QUIZ_TOPICS_DERIVED, scope đổi tạo RESOURCE_STATE_CONFLICT |
| `getQuestionTopics` | questionId -> List topic; resolve scope, kiểm tra quyền và mappings | read-only |
| `replaceQuestionTopics` | questionId + IDs -> TopicChange; lock, kiểm tra Question thuộc Quiz và đã persist, validate mọi topic, ghi/flush rồi synchronize Quiz union | MANDATORY; join transaction LMS-5; sai loại INVALID_TOPIC_ASSOCIATION, chưa persist/scope đổi RESOURCE_STATE_CONFLICT |
| `synchronizeQuizTopics` | activityId -> TopicChange; lock, xác nhận vẫn là Quiz rồi đồng bộ union | MANDATORY; Assignment tạo INVALID_TOPIC_ASSOCIATION |
| `synchronizeQuizMappings` | activityId + classId -> TopicChange; lấy union, validate topic cùng Class và gọi replaceActivityMappings | dùng transaction caller |
| `quizTopicIds` | activityId -> Set UUID; lấy toàn bộ question IDs từ adapter, query canonical mappings và loại trùng | dùng transaction caller |
| `activityTopicIds` | activityId -> Set UUID; đọc IDs trong Activity mappings | dùng transaction caller |
| `replaceActivityMappings` | activityId + Set UUID -> TopicChange; chỉ xóa link cũ không còn, thêm link mới, flush | dùng transaction caller; persistence failure rollback |
| `requireSameScope` | expectedClass + actualClass -> void; khác nhau tạo RESOURCE_CLASS_MISMATCH | dùng transaction caller |
| `TopicChange` constructor | old/new Sets -> kết quả nghiệp vụ với bản sao bất biến; không phải Response DTO | không có transaction riêng |

Các phép đọc/ghi dùng chung lỗi SKILL_TOPIC_NOT_FOUND/RESOURCE_CLASS_MISMATCH từ
SkillTopicChecks. Resource thiếu hoặc không có quyền dùng mã từ adapter. Không thêm
Command chỉ để bọc một tập IDs. Old/new topic sets tiếp tục được trả về cho tích hợp
LMS-9 tương lai, không tính mastery trong task này.

### `service/adaptive/integration/ClassAccess.java`

Interface có sẵn, chỉ bổ sung quy ước lỗi: requireReadAccess dùng CLASS_NOT_FOUND hoặc
CLASS_ACCESS_DENIED; lockForWrite thêm RESOURCE_STATE_CONFLICT cho lifecycle không
cho ghi. Adapter thật phải giữ database Class-level lock đến cuối transaction.

### `service/adaptive/integration/LearningResourceScope.java`

Interface có sẵn, chỉ bổ sung quy ước lỗi: ba phép resolve hierarchy phải ném
LEARNING_RESOURCE_NOT_FOUND nếu thiếu resource, RESOURCE_CLASS_MISMATCH nếu quan hệ
Class không nhất quán. questionIdsForQuizActivity phải trả tập đầy đủ, không dùng tập
rỗng để che module chưa có. Không thêm implementation giả.

### `exception/ErrorCode.java`

Enum chứa mã duy nhất (tên constant), thông báo an toàn và HTTP status. Bao phủ topic,
Class, prerequisite, resource/association, validation, database conflict và lỗi hệ
thống. Đây là metadata dùng chung theo quy ước đã yêu cầu; Service không tự trả
ResponseEntity hoặc serialize JSON. Không tạo exception class riêng cho mỗi mã.

### `exception/AppException.java`

Runtime exception chung, chứa ErrorCode. Constructor một tham số dùng cho lỗi nghiệp
vụ; constructor hai tham số giữ cause khi cần. Message lấy từ ErrorCode, không lấy
thông tin SQL để trả client. Runtime semantics giữ rollback mặc định của Spring.

### `exception/GlobalExceptionHandler.java`

Mở rộng đúng class handler rỗng hiện có thành RestControllerAdvice; không tạo một
hệ thống handler thứ hai. Dùng Map cho error envelope, không thêm feature Response DTO.

| Method | Input -> output; trách nhiệm |
|---|---|
| `handleAppException` | AppException -> ResponseEntity theo ErrorCode; không thay đổi transaction |
| `handleBeanValidation` | jakarta.validation.ConstraintViolationException -> INVALID_REQUEST/400 |
| `handleInvalidRequest` | lỗi binding, type mismatch hoặc JSON không đọc được -> INVALID_REQUEST/400 |
| `handleAccessDenied` | AccessDeniedException/SecurityException -> CLASS_ACCESS_DENIED/403 |
| `handleDataIntegrity` | Spring DataIntegrityViolationException -> conflict/409; duy nhất uq_skill_topics_class_name ánh xạ SKILL_TOPIC_NAME_DUPLICATE; còn lại DATA_CONFLICT; log phía server |
| `handleHibernateConstraint` | org.hibernate.exception.ConstraintViolationException -> nhánh database conflict; không nhầm với Jakarta Bean Validation |
| `handleUnexpected` | exception chưa phân loại -> log error và INTERNAL_ERROR/500; không trả message gốc |
| `response` | ErrorCode -> body code/message/details=[] và status tương ứng; chỉ helper HTTP |

Handler không mở transaction; việc rollback do Spring Service proxy thực hiện khi
exception thoát khỏi method nghiệp vụ.

### `db/migration/V6__require_skill_topic_difficulty.sql`

Migration tiến tới chỉ đặt difficulty_level NOT NULL. Không sửa V2-V5, không đổi
Enum constants, không tự điền mức độ cho dữ liệu cũ. Nếu có NULL thì migration thất
bại để người vận hành xử lý dữ liệu có review; dependency parent UUID vẫn còn.

### Các class kiểm thử

* SkillTopicServiceTest: đổi lời gọi sang Commands; xác nhận trim, 150 ký tự, blank,
  null difficulty/Command, không mutate Entity khi validation lỗi, duplicate name,
  referenced deletion và các mã lỗi Class/topic.
* SkillPrerequisiteValidatorTest: giữ các case graph, đổi assertions sang đúng mã
  self-link/cross-class/missing/cycle. Case null nội bộ vẫn xác nhận NPE.
* SkillPrerequisiteServiceTest: giữ test lock/delta/empty replacement; kiểm tra mã
  cycle/class mismatch/invalid IDs và lỗi quyền.
* SkillTopicAssociationServiceTest: giữ test union, atomic prevalidation và scope;
  kiểm tra mã lỗi association/resource và adapter.
* ErrorAssertions: helper assertError(expected, action) kiểm tra cả AppException và
  ErrorCode, tránh chỉ kiểm tra một loại exception chung.
* GlobalExceptionHandlerTest: MockMvc standalone và Controller fixture chỉ trong test;
  kiểm tra mọi ErrorCode, status/envelope, Bean Validation so với DB/Hibernate,
  duplicate constraint, safe 500 và access denied. Không có production API mới.
* AdaptiveServiceTransactionTest không đổi: tiếp tục kiểm tra REQUIRED commit/rollback,
  MANDATORY và việc Question/Quiz union tham gia transaction upstream.

## D. Các quyết định thiết kế và giới hạn

Command records gom metadata có cùng ý định nghiệp vụ, giúp chữ ký dễ hiểu và không
phụ thuộc Step 5 DTO. ID vẫn riêng để tránh Command vô tình đổi ownership. Không gom
mọi tham số thành class mới. Constructor Injection/final giúp nhìn rõ dependencies,
test đơn giản và không cần Autowired trên constructor duy nhất.

Không có MapStruct hoặc ModelMapper implementation/dependency hiện hành để tái sử
dụng cho LMS-8; ClassMapper hiện chỉ là class rỗng. Giữ setter tường minh cho ba field
được sửa để không overwrite UUID, classId hoặc auditing fields. Không thêm thư viện.

Runtime bean wiring, quyền, database lock và hierarchy adapters vẫn chưa có; ba
Service chưa được tự động đăng ký. Không dùng default adapter để giả hoàn tất.
Các unit/proxy/MockMvc test không chứng minh PostgreSQL integration hoặc concurrency.
V6 cũng chưa chạy trên PostgreSQL. Phải giải quyết parent schema/migration ordering,
dữ liệu NULL lịch sử và adapter thật trước khi công bố tích hợp hoàn chỉnh.

## Kiểm thử đã thực hiện — 2026-10-30

JDK 25.0.4.1, Maven 3.9.12; dùng cấu hình proxy/truststore/cache của session bên ngoài
repository, không đổi pom.xml hoặc application.yaml.

```text
mvn -Dtest=SkillTopicServiceTest,SkillPrerequisiteServiceTest,SkillTopicAssociationServiceTest,AdaptiveServiceTransactionTest,SkillPrerequisiteValidatorTest,GlobalExceptionHandlerTest test
```

84/84 test pass, không failure/error/skip. `mvn clean verify` chạy 88 test: 84 pass,
4 test Co3103G11LmsInstanceApplicationTests lỗi ApplicationContext vì PostgreSQL tại
localhost:5432 từ chối kết nối. Full verify không pass. Không có kết quả PostgreSQL
integration thành công và V6 chưa được thực thi trên database.
