package com.flightstats.hub.dao;

import com.flightstats.hub.model.ChannelConfig;
import com.flightstats.hub.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public class TtlEnforcer {
    private final static Logger logger = LoggerFactory.getLogger(TtlEnforcer.class);

    public static void enforce(String path, ChannelService channelService,
                               Consumer<ChannelConfig> channelConsumer) {
        try {
            File spokeRoot = new File(path);
            Set<String> dirSet = new HashSet<>(Arrays.asList(spokeRoot.list()));
            Iterable<ChannelConfig> channels = channelService.getChannels();
            Set<String> channelSet = new HashSet<>();
            for (ChannelConfig channel : channels) {
                channelSet.add(channel.getName());
                channelConsumer.accept(channel);
            }
            dirSet.removeAll(channelSet);
            dirSet.remove("lost+found");
            for (String dir : dirSet) {
                String dirPath = path + "/" + dir;
                logger.info("removing dir without channel {}", dirPath);
                FileUtil.runCommand(new String[]{"rm", "-rf", dirPath}, 1);
            }
        } catch (Exception e) {
            logger.warn("unble to run " + path, e);
        }
    }
}
