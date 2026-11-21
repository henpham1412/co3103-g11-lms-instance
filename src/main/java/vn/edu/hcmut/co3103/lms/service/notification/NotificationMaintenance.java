package vn.edu.hcmut.co3103.lms.service.notification;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.co3103.lms.repository.notification.DeadlineReminderRepository;

import java.time.Clock;
import java.time.ZoneOffset;

@Component
public class NotificationMaintenance {
    private final DeadlineReminderRepository reminders;
    private final NotificationService notifications;
    private final Clock clock;

    public NotificationMaintenance(DeadlineReminderRepository reminders, NotificationService notifications, Clock clock) {
        this.reminders = reminders;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Scheduled(cron = "${lms.notifications.reminder-cron:30 */5 * * * *}", zone = "UTC")
    @Transactional
    public void sendDeadlineReminders() {
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        reminders.sendUpcoming(now, now.plusHours(24));
    }

    @Scheduled(cron = "${lms.notifications.cleanup-cron:0 3 * * * *}", zone = "UTC")
    public void deleteExpired() {
        notifications.deleteExpired();
    }
}
