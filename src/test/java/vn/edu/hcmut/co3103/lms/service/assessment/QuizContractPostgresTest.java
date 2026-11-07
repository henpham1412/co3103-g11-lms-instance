package vn.edu.hcmut.co3103.lms.service.assessment;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport;
import vn.edu.hcmut.co3103.lms.dto.request.QuestionCreateRequest;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ActivityRepository;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.net.*;
import java.net.http.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(QuizContractPostgresTest.Clocks.class)
class QuizContractPostgresTest {
    static final JwtTestSupport JWT = jwt();
    static JwtTestSupport jwt() { try { return new JwtTestSupport(); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @TestConfiguration static class Clocks { @Bean @Primary Clock clock() { return JwtTestSupport.CLOCK; } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> JwtTestSupport.ISSUER);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT::jwksUri);
        r.add("lms.quiz.expiry-scan-ms", () -> "3600000");
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager manager;
    @Autowired QuizCreationService factory;
    @Autowired ActivityRepository activities;
    UUID owner,student,course,chapter,lesson,activity,quiz,question;
    String token,studentToken;
    TransactionTemplate tx() { return new TransactionTemplate(manager); }
    @BeforeEach void seed() throws Exception {
        owner=UUID.randomUUID();student=UUID.randomUUID();course=UUID.randomUUID();chapter=UUID.randomUUID();lesson=UUID.randomUUID();
        for (UUID id:List.of(owner,student)) db.update("insert into lms_user_access(user_id,full_name,email,lms_role,access_status) values(?,?,?,?,'ACTIVE')",id,"Review",id+"@example.invalid",id.equals(owner)?"TUTOR":"STUDENT");
        db.update("insert into class(class_id,tutor_id,class_code,title,enrollment_policy,status) values(?,?,?,'Quiz contract','OPEN','ACTIVE')",course,owner,"Q"+UUID.randomUUID().toString().substring(0,8));
        db.update("insert into class_member(class_member_id,class_id,student_id,status) values(?,?,?,'ACTIVE')",UUID.randomUUID(),course,student);
        db.update("insert into chapter(chapter_id,class_id,title,sequence_order,status) values(?,?,'Chapter',0,'PUBLISHED')",chapter,course);
        db.update("insert into lesson(lesson_id,chapter_id,title,sequence_order,status) values(?,?,'Lesson',0,'PUBLISHED')",lesson,chapter);
        var claims=JWT.claims();claims.put("sub",owner.toString());token=JWT.token(claims);claims=JWT.claims();claims.put("sub",student.toString());studentToken=JWT.token(claims);
        var made=tx().execute(s -> { var a=activities.saveAndFlush(new Activity(lesson,"Quiz",null,Activity.ActivityType.QUIZ,0,Chapter.VisibilityStatus.PUBLISHED));return factory.createForActivity(a.getActivityId(),owner,command()); });
        activity=made.activityId();quiz=made.quizId();question=db.queryForObject("select question_id from question where quiz_id=?",UUID.class,quiz);
    }
    QuizCreation command() { return new QuizCreation(20,null,null,null,OffsetDateTime.now().plusDays(2),null,List.of(new QuestionCreateRequest("MCQ_SINGLE","Question",java.math.BigDecimal.ONE,null,0,List.of(new QuestionCreateRequest.Option("A",true,0),new QuestionCreateRequest.Option("B",false,1)),null))); }
    @AfterEach void cleanup() {
        db.update("delete from attempt_answer_option where answer_id in (select answer_id from attempt_answer where attempt_id in (select attempt_id from quiz_attempt where quiz_id=?))",quiz);
        db.update("delete from attempt_answer where attempt_id in (select attempt_id from quiz_attempt where quiz_id=?)",quiz);
        db.update("delete from quiz_attempt where quiz_id=?",quiz);
        db.update("delete from question_option where question_id in (select question_id from question where quiz_id=?)",quiz);
        db.update("delete from question where quiz_id=?",quiz);db.update("delete from quiz where quiz_id=?",quiz);
        db.update("delete from activity where lesson_id=?",lesson);db.update("delete from lesson where lesson_id=?",lesson);db.update("delete from chapter where chapter_id=?",chapter);db.update("delete from class_member where class_id=?",course);db.update("delete from class where class_id=?",course);
        db.update("delete from lms_user_access where user_id in (?,?)",owner,student);
    }
    HttpResponse<String> request(String method,String path,String body,String bearer) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Authorization","Bearer "+bearer).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @ParameterizedTest @ValueSource(strings={"{\"openingTimeProvided\":true}","{\"openingTimeProvided\":null}","{\"unknown\":null}","{\"time_limit_minutes\":1.9}","{\"attempts_allowed\":\"2\"}","{\"attempts_allowed\":true}","{}","{\"deadline\":null}"})
    void invalidQuizPatchDoesNotWrite(String payload) throws Exception {
        var before=db.queryForMap("select * from quiz where quiz_id=?",quiz);
        var response=request("PATCH","/activities/"+activity+"/quiz",payload,token);
        assertThat(response.statusCode()).isEqualTo(400);assertThat(response.body()).contains("VALIDATION_ERROR");
        assertThat(db.queryForMap("select * from quiz where quiz_id=?",quiz)).isEqualTo(before);
    }
    @Test void nullAndOmittedAreDistinct() throws Exception {
        db.update("update quiz set opening_time=now() where quiz_id=?",quiz);
        var opening=db.queryForMap("select opening_time from quiz where quiz_id=?",quiz);
        assertThat(request("PATCH","/activities/"+activity+"/quiz","{\"attempts_allowed\":2}",token).statusCode()).isEqualTo(200);
        assertThat(db.queryForMap("select opening_time from quiz where quiz_id=?",quiz)).isEqualTo(opening);
        assertThat(request("PATCH","/activities/"+activity+"/quiz","{\"opening_time\":null}",token).statusCode()).isEqualTo(200);
        assertThat(db.queryForObject("select opening_time from quiz where quiz_id=?",Object.class,quiz)).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"100000000","1.001","-1"})
    void pointsOutsideNumericPrecisionReject(String points) throws Exception {
        var before=db.queryForMap("select * from question where question_id=?",question);
        var response=request("PATCH","/questions/"+question,"{\"points_awarded\":"+points+"}",token);
        assertThat(response.statusCode()).isEqualTo(400);assertThat(response.body()).contains("INVALID_QUESTION");
        assertThat(db.queryForMap("select * from question where question_id=?",question)).isEqualTo(before);
    }
    @Test void lastPublishedQuestionCannotBeDeletedButHiddenCan() throws Exception {
        assertThat(request("DELETE","/questions/"+question,null,token).statusCode()).isEqualTo(409);
        assertThat(request("PATCH","/activities/"+activity,"{\"status\":\"HIDDEN\"}",token).statusCode()).isEqualTo(200);
        assertThat(request("DELETE","/questions/"+question,null,token).statusCode()).isEqualTo(204);
        assertThatThrownBy(() -> tx().execute(s -> {factory.requirePublishable(activity,owner);return null;})).isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("select status from activity where activity_id=?",String.class,activity)).isEqualTo("HIDDEN");
    }
    @Test void creationInitializesAuditAndRejectsDuplicateAndWrongType() {
        assertThat(db.queryForMap("select created_at,updated_at,attempts_allowed,grading_method from quiz where quiz_id=?",quiz)).doesNotContainValue(null).containsEntry("attempts_allowed",1).containsEntry("grading_method","HIGHEST");
        assertThatThrownBy(() -> factory.createForActivity(activity,owner,command())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx().execute(s -> factory.createForActivity(activity,owner,command()))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> tx().execute(s -> {var a=activities.saveAndFlush(new Activity(lesson,"Wrong",null,Activity.ActivityType.ASSIGNMENT,1,Chapter.VisibilityStatus.HIDDEN));return factory.createForActivity(a.getActivityId(),owner,command());})).isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("select count(*) from activity where lesson_id=?",Integer.class,lesson)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void wholeCallerGraphRollsBackOnOptionFailure(boolean deferred) {
        String name="quiz_contract_"+course.toString().replace("-","");
        db.execute("create function "+name+"() returns trigger language plpgsql as $$ begin if NEW.content='B' then raise exception 'test option failure' using errcode='23514'; end if; return NEW; end $$");
        db.execute("create constraint trigger "+name+" after insert on question_option deferrable initially "+(deferred?"deferred":"immediate")+" for each row execute function "+name+"()");
        try {
            assertThatThrownBy(() -> tx().execute(s -> {var a=activities.saveAndFlush(new Activity(lesson,"Rollback",null,Activity.ActivityType.QUIZ,1,Chapter.VisibilityStatus.HIDDEN));return factory.createForActivity(a.getActivityId(),owner,command());})).isInstanceOf(RuntimeException.class);
            assertThat(db.queryForObject("select count(*) from activity where lesson_id=?",Integer.class,lesson)).isEqualTo(1);
            assertThat(db.queryForObject("select count(*) from quiz where activity_id=?",Integer.class,activity)).isEqualTo(1);
            assertThat(db.queryForObject("select count(*) from question where quiz_id=?",Integer.class,quiz)).isEqualTo(1);
        } finally {db.execute("drop trigger "+name+" on question_option");db.execute("drop function "+name+"()");}
    }

    void awaitLocks(int count) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(System.nanoTime()<end) {
            Integer waiting=db.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock'",Integer.class);
            if(waiting>=count) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Expected queued PostgreSQL locks: "+count);
    }
    @Test void queuedQuestionPatchesPreservePointsAndContent() throws Exception {
        try(var connection=dataSource.getConnection();var pool=Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("select class_id from class where class_id=? for update")) { statement.setObject(1,course);statement.executeQuery(); }
            var first=pool.submit(() -> request("PATCH","/questions/"+question,"{\"points_awarded\":2,\"points_deducted\":0.5}",token));
            awaitLocks(1);
            var second=pool.submit(() -> request("PATCH","/questions/"+question,"{\"content\":\"Concurrent title\"}",token));
            awaitLocks(2);connection.commit();
            assertThat(first.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(second.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            var row=db.queryForMap("select content,points_awarded,points_deducted from question where question_id=?",question);
            assertThat(row.get("content")).isEqualTo("Concurrent title");
            assertThat((java.math.BigDecimal)row.get("points_awarded")).isEqualByComparingTo("2");
            assertThat((java.math.BigDecimal)row.get("points_deducted")).isEqualByComparingTo("0.5");
        }
    }
    @ParameterizedTest @ValueSource(strings={"SAVE","SUBMIT","MEMBER"})
    void lifecycleOrMembershipTransitionWinsBeforeStudentWrite(String action) throws Exception {
        var start=request("POST","/quizzes/"+quiz+"/attempts",null,studentToken);
        assertThat(start.statusCode()).isEqualTo(201);
        UUID attempt=db.queryForObject("select attempt_id from quiz_attempt where quiz_id=?",UUID.class,quiz);
        UUID option=db.queryForObject("select option_id from question_option where question_id=? and is_correct",UUID.class,question);
        try(var connection=dataSource.getConnection();var pool=Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("select class_id from class where class_id=? for update")) { statement.setObject(1,course);statement.executeQuery(); }
            var transition=pool.submit(() -> {
                if(action.equals("MEMBER")) {
                    UUID member=db.queryForObject("select class_member_id from class_member where class_id=? and student_id=?",UUID.class,course,student);
                    return request("DELETE","/classes/"+course+"/members/"+member,null,token);
                }
                return request("PATCH","/classes/"+course,"{\"status\":\"ARCHIVED\"}",token);
            });
            awaitLocks(1);
            var write=pool.submit(() -> action.equals("SUBMIT")
                    ?request("POST","/quiz-attempts/"+attempt+"/submit",null,studentToken)
                    :request("PUT","/quiz-attempts/"+attempt+"/answers/"+question,"{\"selected_option_ids\":[\""+option+"\"]}",studentToken));
            awaitLocks(2);connection.commit();
            assertThat(transition.get(15,TimeUnit.SECONDS).statusCode()).isIn(200,204);
            assertThat(write.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(action.equals("MEMBER")?403:409);
            assertThat(db.queryForObject("select status from quiz_attempt where attempt_id=?",String.class,attempt)).isEqualTo("IN_PROGRESS");
            assertThat(db.queryForObject("select count(*) from attempt_answer where attempt_id=?",Integer.class,attempt)).isZero();
        }
    }

    @ParameterizedTest @ValueSource(strings={"1.9","\"1\"","true"})
    void questionOrderUsesStrictJsonIntegers(String value) throws Exception {
        var before=db.queryForMap("select * from question where question_id=?",question);
        var response=request("PATCH","/questions/"+question,"{\"order_index\":"+value+"}",token);
        assertThat(response.statusCode()).isEqualTo(400);assertThat(response.body()).contains("INVALID_QUESTION");
        assertThat(db.queryForMap("select * from question where question_id=?",question)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"EMPTY","DEADLINE","OPENING","TIME","ATTEMPTS","GRADING","SCORE","DUPLICATE_ORDER"})
    void invalidCreationRollsBackCallerActivity(String invalid) {
        var c=command();
        QuizCreation bad=new QuizCreation(invalid.equals("TIME")?0:c.timeLimitMinutes(),
                invalid.equals("ATTEMPTS")?0:null,invalid.equals("GRADING")?"OTHER":null,
                invalid.equals("OPENING")?c.deadline().plusHours(1):null,
                invalid.equals("DEADLINE")?OffsetDateTime.now().minusDays(1):c.deadline(),
                invalid.equals("SCORE")?new java.math.BigDecimal("101"):null,
                invalid.equals("EMPTY")?List.of():invalid.equals("DUPLICATE_ORDER")?List.of(c.questions().getFirst(),c.questions().getFirst()):c.questions());
        assertThatThrownBy(() -> tx().execute(s -> {var a=activities.saveAndFlush(new Activity(lesson,"Invalid",null,Activity.ActivityType.QUIZ,1,Chapter.VisibilityStatus.HIDDEN));return factory.createForActivity(a.getActivityId(),owner,bad);})).isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("select count(*) from activity where lesson_id=?",Integer.class,lesson)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from quiz where activity_id=?",Integer.class,activity)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"0","99999999.99","1.2300"})
    void representablePointBoundariesPersistExactly(String value) throws Exception {
        assertThat(request("PATCH","/questions/"+question,"{\"points_awarded\":"+value+"}",token).statusCode()).isEqualTo(200);
        assertThat(db.queryForObject("select points_awarded from question where question_id=?",java.math.BigDecimal.class,question)).isEqualByComparingTo(value);
    }
    @ParameterizedTest @ValueSource(strings={"1.9","\"0\"","true"})
    void optionOrderRejectsCoercionWithoutWriting(String value) throws Exception {
        var before=db.queryForList("select * from question_option where question_id=? order by order_index",question);
        String body="{\"options\":[{\"content\":\"A\",\"is_correct\":true,\"order_index\":"+value+"},{\"content\":\"B\",\"is_correct\":false,\"order_index\":1}]}";
        var response=request("PATCH","/questions/"+question,body,token);
        assertThat(response.statusCode()).isEqualTo(400);assertThat(response.body()).contains("INVALID_QUESTION");
        assertThat(db.queryForList("select * from question_option where question_id=? order by order_index",question)).isEqualTo(before);
    }
}
