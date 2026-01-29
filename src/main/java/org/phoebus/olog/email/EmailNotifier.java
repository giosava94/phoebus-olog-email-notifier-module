package org.phoebus.olog.email;

import org.phoebus.olog.entity.Attachment;
import org.phoebus.olog.entity.Log;
import org.phoebus.olog.notification.LogEntryNotifier;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.springsupport.SimpleJavaMailSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import com.google.auto.service.AutoService;

import jakarta.activation.DataSource;
import jakarta.mail.util.ByteArrayDataSource;

import org.phoebus.olog.entity.Tag;
import org.phoebus.olog.entity.State;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.HashMap;

@AutoService(LogEntryNotifier.class)
@Component
@Import(SimpleJavaMailSpringSupport.class)
public class EmailNotifier implements LogEntryNotifier {

    private Logger logger = Logger.getLogger(EmailNotifier.class.getName());

    @Autowired
    private TagEmailMapPreferences tagEmailMapPreferences;

    @Autowired
    private Mailer mailer;

    private Map<String, List<String>> tagEmailMap = new HashMap<>();

    @Override
    public void notify(Log log) {
        Set<Tag> tags = log.getTags();
        List<String> emailList = new ArrayList<>();
        List<String> tagNames = new ArrayList<>();

        String logTitle = log.getTitle();
        String senderEmail = ConfigLoader.getStringProperty(Property.DEFAULT_FROM_ADDRESS);
        String senderName = ConfigLoader.getStringProperty(Property.DEFAULT_FROM_NAME);

        SortedSet<Attachment> logAttachments = log.getAttachments();
        logger.log(Level.INFO, "Retrieved " + logAttachments.size() + " log attachments");
        List<AttachmentResource> attachmentResources = logAttachments.stream()
                .map(attachment -> {
                    logger.log(Level.INFO, "Processing attachment: " + attachment.getFilename());
                    String fname = attachment.getFilename();
                    String description = attachment.getFileMetadataDescription();
                    DataSource dataSource;
                    try (InputStream attachmentStream = attachment.getAttachment().getInputStream()) {
                        dataSource = new ByteArrayDataSource(attachmentStream, "application/octet-stream");
                    } catch (Exception e) {
                        logger.log(Level.SEVERE, "Error reading attachment stream", e);
                        return null;
                    }
                    AttachmentResource resource = new AttachmentResource(fname, dataSource, description);
                    logger.log(Level.INFO, "Retrieved attachment resource: " + fname);
                    return resource;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (attachmentResources.size() != logAttachments.size()) {
            logger.log(Level.SEVERE, "Failed to read some attachments");
        }

        if (senderEmail == null) {
            logger.log(Level.WARNING, "Default sender email was not set in properties file");
            senderEmail = "olog-email@email.com";
        }
        if (senderName == null) {
            logger.log(Level.WARNING, "Default sender name was not set in properties file");
            senderName = "olog emailer";
        }

        logger.log(Level.INFO, "Email From " + senderName + " " + senderEmail);
        logger.log(Level.INFO, "Logbook Email Started");

        // Get Tag to Email Map
        try {
            tagEmailMap = tagEmailMapPreferences.tagEmailMap();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "There was an error reading tag email JSON", e);
        }

        // Send Email
        try {
            logger.log(Level.INFO, tagEmailMap.toString());
            for (Tag tag : tags) {
                if (tag.getState() == State.Active) {
                    String tagName = tag.getName();
                    tagNames.add(tagName);
                    if (tagEmailMap.get(tagName) != null) {
                        emailList.addAll(tagEmailMap.get(tagName));
                    }
                }
            }
            Email logbookEmail = createLogEmail(emailList, senderName, senderEmail, logTitle, log.getDescription(),
                    tagNames, attachmentResources);
            if (!emailList.isEmpty()) {
                logger.log(Level.INFO, "Email being sent to: " + emailList);
                mailer.sendMail(logbookEmail);
            } else {
                logger.log(Level.INFO, "Email list is empty");
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "There was an error sending email", e);
        }
    }

    public Email createLogEmail(List<String> emailList, String senderName, String senderEmail, String subject,
            String body, List<String> tagNames, List<AttachmentResource> attachmentList) {
        return EmailBuilder.startingBlank()
                .from(senderName, senderEmail)
                .toMultiple(emailList)
                .withSubject(subject)
                .withPlainText(tagNames.toString())
                .withPlainText(body)
                .withAttachments(attachmentList)
                .buildEmail();
    }
}