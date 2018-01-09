/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.networkupstools.internal;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang.StringUtils;
import org.networkupstools.jnut.Client;
import org.networkupstools.jnut.Device;
import org.networkupstools.jnut.Variable;
import org.networkupstools.jnut.NutException;
import org.openhab.binding.networkupstools.NetworkUpsToolsBindingProvider;
import org.openhab.core.binding.AbstractActiveBinding;
import org.openhab.core.items.Item;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.items.StringItem;
import org.openhab.core.library.items.SwitchItem;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.State;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

/**
 * The RefreshService polls all configured ups parameters with a configurable
 * interval and post all values to the internal event bus. The interval is 1
 * minute by default and can be changed via openhab.cfg.
 *
 * @author jaroslawmazgaj
 * @since 1.7.0
 */
public class NetworkUpsToolsBinding extends AbstractActiveBinding<NetworkUpsToolsBindingProvider>
        implements ManagedService {

    private static final Logger logger = LoggerFactory.getLogger(NetworkUpsToolsBinding.class);

    private Map<String, NutConfig> upses = new HashMap<String, NutConfig>();

    /**
     * the refresh interval which is used to poll values from the NetworkUpsTools
     * server (optional, defaults to 60000ms)
     */
    private long refreshInterval = 60000;

    /**
     * @{inheritDoc}
     */
    @Override
    protected long getRefreshInterval() {
        return refreshInterval;
    }

    /**
     * @{inheritDoc}
     */
    @Override
    protected String getName() {
        return "NetworkUpsTools Refresh Service";
    }

    /**
     * @{inheritDoc}
     */
    @Override
    protected void execute() {
        Multimap<String, ItemDefinition> items = HashMultimap.create();
        for (NetworkUpsToolsBindingProvider provider : providers) {
            for (String itemName : provider.getItemNames()) {
                String name = provider.getUps(itemName);
                Class<? extends Item> itemType = provider.getItemType(itemName);
                String property = provider.getProperty(itemName);
                items.put(name, new ItemDefinition(itemName, itemType, property));
            }
        }

        for (String name : items.keySet()) {
            NutConfig nut = upses.get(name);
            if (nut == null) {
                logger.warn("No configuration found for UPS with name '{}'", name);
                continue;
            }

            Client client = null;
            
            try {
                client = new Client(nut.host, nut.port, nut.login, nut.pass);
            } catch (UnknownHostException e) {
                logger.warn("Failed to create NUT client because host {} is unknown.", nut.host);
                return;
            } catch (IOException e) {
                logger.warn("Failed to create NUT client due to communication error: {}", e.getMessage());
                return;
            } catch (Exception e) {
                logger.warn("Failed to create NUT client due to unexpected error: {}", e.getMessage());
                return;
            }

            Device device = null;

            try {
                device = client.getDevice(nut.device);
            } catch (IOException e) {
                logger.warn("Couldn't create a device for '{}' due to communication error: {}", nut.device, e.getMessage());
                return;
            } catch (NutException e) {
                logger.warn("Couldn't create a device for '{}' due to unexpected error: {}", nut.device, e.getMessage());
                return;
            }
            
            try {
                for (ItemDefinition definition : items.get(name)) {
                    Variable variable = device.getVariable(definition.property);
                    String value = variable.getValue();
                    Class<? extends Item> itemType = definition.itemType;
                    String itemName = definition.itemName;

                    // Change to a state
                    State state = null;
                    if (itemType.isAssignableFrom(StringItem.class)) {
                        state = StringType.valueOf(value);
                    } else if (itemType.isAssignableFrom(NumberItem.class)) {
                        state = DecimalType.valueOf(value);
                    } else if (itemType.isAssignableFrom(SwitchItem.class)) {
                        state = OnOffType.valueOf(value);
                    }

                    if (state != null) {
                        eventPublisher.postUpdate(itemName, state);
                    } else {
                        logger.warn(
                                "'{}' couldn't be parsed to a State. Valid types are String, Number, and Switch",
                                variable);
                    }
                }
            } catch (Exception e) {
                logger.warn("Unexpected error occurred while processing item definitions: {}", e.getMessage());
            } finally {
                if (client != null) {
                    client.disconnect();
                }
            }
        }
    }

    protected void addBindingProvider(NetworkUpsToolsBindingProvider bindingProvider) {
        super.addBindingProvider(bindingProvider);
    }

    protected void removeBindingProvider(NetworkUpsToolsBindingProvider bindingProvider) {
        super.removeBindingProvider(bindingProvider);
    }

    /**
     * @{inheritDoc}
     */
    @Override
    public void updated(Dictionary<String, ?> config) throws ConfigurationException {
        String refreshIntervalString = Objects.toString(config.get("refresh"), null);
        if (StringUtils.isNotBlank(refreshIntervalString)) {
            refreshInterval = Long.parseLong(refreshIntervalString);
        }

        Map<String, NutConfig> newUpses = new HashMap<String, NutConfig>();

        Enumeration<String> keys = config.keys();

        while (keys.hasMoreElements()) {
            String key = keys.nextElement();

            if ("refresh".equals(key) || "service.pid".equals(key)) {
                continue;
            }

            String[] parts = key.split("\\.");
            String name = parts[0];
            String prop = parts[1];
            String value = Objects.toString(config.get(key), null);

            NutConfig nutConfig = newUpses.get(name);
            if (nutConfig == null) {
                nutConfig = new NutConfig();
                newUpses.put(name, nutConfig);
            }

            if ("device".equalsIgnoreCase(prop)) {
                nutConfig.device = value;
            } else if ("host".equalsIgnoreCase(prop)) {
                nutConfig.host = value;
            } else if ("login".equalsIgnoreCase(prop)) {
                nutConfig.login = value;
            } else if ("pass".equalsIgnoreCase(prop)) {
                nutConfig.pass = value;
            } else if ("port".equalsIgnoreCase(prop)) {
                nutConfig.port = Integer.parseInt(value);
            }
        }

        upses = newUpses;

        setProperlyConfigured(true);
    }

    /**
     * This is an internal data structure to store nut server configuration
     */
    class NutConfig {
        String device;
        String host = "localhost";
        String login = "";
        String pass = "";
        int port = 3493;
    }

    /**
     * This is an internal data structure to store item definition details for given nut server
     */
    class ItemDefinition {
        String itemName;
        Class<? extends Item> itemType;
        String property;

        public ItemDefinition(String itemName, Class<? extends Item> itemType, String property) {
            this.itemName = itemName;
            this.itemType = itemType;
            this.property = property;
        }
    }
}
