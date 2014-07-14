package com.flightstats.hub.service;

import com.flightstats.hub.dao.ChannelService;
import com.flightstats.hub.dao.encryption.AuditChannelService;
import com.flightstats.hub.model.ChannelConfiguration;
import com.flightstats.hub.model.exception.ConflictException;
import com.flightstats.hub.model.exception.InvalidRequestException;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.inject.Inject;

public class ChannelValidator {
    private final ChannelService channelService;

    @Inject
    public ChannelValidator(ChannelService channelService) {
        this.channelService = channelService;
    }

    public void validate(ChannelConfiguration request, boolean isCreation) throws InvalidRequestException, ConflictException {
        Optional<String> channelNameOptional =  Optional.absent();
        if (request != null) {
            channelNameOptional = Optional.fromNullable(request.getName());
        }

        validateNameWasGiven(channelNameOptional);
        String channelName = channelNameOptional.get().trim();
        ensureNotAllBlank(channelName);
        ensureSize(channelName);
        checkForInvalidCharacters(channelName);
        if (isCreation) {
            validateChannelUniqueness(channelName);
        }
        validateRate(request);
        validateContentSize(request);
        validateTTL(request);
        validateDescription(request);
        validateTags(request);
    }

    private void validateTags(ChannelConfiguration request) {
        if (request.getTags().size() > 20) {
            throw new InvalidRequestException("{\"error\": \"Channels are limited to 20 tags\"}");
        }
        for (String tag : request.getTags()) {
            if (!tag.matches("^[a-zA-Z0-9\\:\\-]+$")) {
                throw new InvalidRequestException("{\"error\": \"Tags must only contain characters a-z, A-Z, and 0-9\"}");
            }
            if (tag.length() > 48) {
                throw new InvalidRequestException("{\"error\": \"Tags must be less than 48 bytes. \"}");
            }
        }
    }

    private void validateDescription(ChannelConfiguration request) {
        if (request.getDescription().length() > 1024) {
            throw new InvalidRequestException("{\"error\": \"Description must be less than 1024 bytes. \"}");
        }
    }

    private void validateTTL(ChannelConfiguration request) throws InvalidRequestException {
        if (request.getTtlDays() <= 0) {
            throw new InvalidRequestException("{\"error\": \"TTL must be greater than 0 (zero) \"}");
        }
    }


    private void validateContentSize(ChannelConfiguration request) throws InvalidRequestException {
        if (request.getContentSizeKB() <= 0) {
            throw new InvalidRequestException("{\"error\": \"Content Size must be greater than 0 (zero) \"}");
        }
    }

    private void validateRate(ChannelConfiguration request) throws InvalidRequestException {
        if (request.getPeakRequestRateSeconds() <= 0) {
            throw new InvalidRequestException("{\"error\": \"Peak Request Rate must be greater than 0 (zero) \"}");
        }
    }

    private void validateNameWasGiven(Optional<String> channelName) throws InvalidRequestException {
        if ((channelName == null) || !channelName.isPresent()) {
            throw new InvalidRequestException("{\"error\": \"Channel name wasn't given\"}");
        }
    }

    private void ensureSize(String name) throws InvalidRequestException {
        int maxLength = 48;
        if (AuditChannelService.isAuditChannel(name)) {
            maxLength += AuditChannelService.AUDIT.length();
        }
        if (name.length() > maxLength) {
            throw new InvalidRequestException("{\"error\": \"Channel name is too long " + name + "\"}");
        }
    }

    private void ensureNotAllBlank(String channelName) throws InvalidRequestException {
        if (Strings.nullToEmpty(channelName).trim().isEmpty()) {
            throw new InvalidRequestException("{\"error\": \"Channel name cannot be blank\"}");
        }
    }

    private void checkForInvalidCharacters(String channelName) throws InvalidRequestException {
        if (!channelName.matches("^[a-zA-Z0-9_]+$")) {
            throw new InvalidRequestException("{\"error\": \"Channel name " + channelName + "must only contain characters a-z, A-Z, and 0-9\"}");
        }
    }

    private void validateChannelUniqueness(String channelName) throws ConflictException {
        if (channelService.channelExists(channelName)) {
            throw new ConflictException("{\"error\": \"Channel name " + channelName + " already exists\"}");
        }
    }
}