/*
 * Copyright 2019 Google LLC
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.actions.api.smarthome.ExecuteRequest;
import com.google.api.core.ApiFuture;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;

public class MyDataStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(MySmartHomeApp.class);
    private static MyDataStore ourInstance = new MyDataStore();

    Firestore database;

    private static MyMqtt mqtt;

    static {
        try {
            mqtt = new MyMqtt();
        } catch (MqttException | IOException e) {
            LOGGER.error("Error when creating sample mqtt " + e);
        }
    }

    public MyDataStore() {
        // Use a service account
        try {
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
            String projectId = System.getenv("GOOGLE_CLOUD_PROJECT");
            FirebaseOptions options =
                    new FirebaseOptions.Builder().setCredentials(credentials).setProjectId(projectId).build();
            FirebaseApp.initializeApp(options);
            database = FirestoreClient.getFirestore();
        } catch (Exception e) {
            LOGGER.error("ERROR: invalid service account credentials. See README.");
            LOGGER.error(e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public static MyDataStore getInstance() {
        return ourInstance;
    }

    public List<QueryDocumentSnapshot> getDevices(String userId)
            throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> deviceQuery =
                database.collection("users").document(userId).collection("devices").get();
        return deviceQuery.get().getDocuments();
    }

    public String getUserId(String token) throws ExecutionException, InterruptedException {
        if (token == null) {
            token = "Bearer 123access";
        }
        ApiFuture<QuerySnapshot> userQuery =
                database.collection("users").whereEqualTo("fakeAccessToken", token.substring(7)).get();
        QuerySnapshot usersSnapshot = userQuery.get();
        List<QueryDocumentSnapshot> users = usersSnapshot.getDocuments();

        DocumentSnapshot user;
        try {
            user = users.get(0);
        } catch (Exception e) {
            LOGGER.error("no user found!");
            throw e;
        }

        return user.getId();
    }

    public Boolean isHomegraphEnabled(String userId) throws ExecutionException, InterruptedException {
        DocumentSnapshot user = database.collection("users").document(userId).get().get();
        return (Boolean) user.get("homegraph");
    }

    public void setHomegraph(String userId, Boolean enable) {
        DocumentReference user = database.collection("users").document(userId);
        user.update("homegraph", enable);
    }

    public void updateDevice(
            String userId, String deviceId, Map<String, Object> states, Map<String, String> params)
            throws ExecutionException, InterruptedException {
        DocumentReference device =
                database.collection("users").document(userId).collection("devices").document(deviceId);
        if (states != null) {
            device.update("states", states).get();
        }
        if (params.containsKey("name")) {
            String name = params.get("name");
            device.update("name", name != null ? name : FieldValue.delete()).get();
        }
        if (params.containsKey("nickname")) {
            String nickname = params.get("nickname");
            device.update("nickname", nickname != null ? nickname : FieldValue.delete()).get();
        }
        if (params.containsKey("errorCode")) {
            String errorCode = params.get("errorCode");
            device.update("errorCode", errorCode != null ? errorCode : FieldValue.delete()).get();
        }
        if (params.containsKey("tfa")) {
            String tfa = params.get("tfa");
            device.update("tfa", tfa != null ? tfa : FieldValue.delete()).get();
        }
        if (params.containsKey("localDeviceId")) {
            String localDeviceId = params.get("localDeviceId");
            if (localDeviceId != null) {
                Map<String, Object> otherDeviceId = new HashMap<>();
                otherDeviceId.put("deviceId", localDeviceId);
                List<Object> otherDeviceIds = new ArrayList<>();
                otherDeviceIds.add(otherDeviceId);
                device.update("otherDeviceIds", otherDeviceIds).get();
            } else {
                device.update("otherDeviceIds", FieldValue.delete()).get();
            }
        }
    }

    public void addDevice(String userId, Map<String, Object> data)
            throws ExecutionException, InterruptedException {
        String deviceId = (String) data.get("deviceId");
        database
            .collection("users")
            .document(userId)
            .collection("devices")
            .document(deviceId)
            .set(data)
            .get();
    }

    public void deleteDevice(String userId, String deviceId)
            throws ExecutionException, InterruptedException {
        database
            .collection("users")
            .document(userId)
            .collection("devices")
            .document(deviceId)
            .delete()
            .get();
    }

    public Map<String, Object> getState(String userId, String deviceId)
            throws ExecutionException, InterruptedException {
        DocumentSnapshot device =
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .get()
                    .get();
        return (Map<String, Object>) device.get("states");
    }

    public Map<String, Object> execute(
            String userId, String deviceId, ExecuteRequest.Inputs.Payload.Commands.Execution execution)
            throws Exception {

        DocumentSnapshot device =
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .get()
                    .get();
        Map<String, Object> deviceStates = (Map<String, Object>) device.getData().get("states");
        Map<String, Object> states = new HashMap<>();

        // if (device.contains("states")) {
        if (!deviceStates.isEmpty()) {
            states.putAll(deviceStates);
        }

        if (!(Boolean) states.get("online")) {
            throw new Exception("deviceOffline");
        }

        if (device.contains("errorCode") && !device.getString("errorCode").isEmpty()) {
            throw new Exception(device.getString("errorCode"));
        }

        if (device.contains("tfa")) {
            if (device.getString("tfa").equals("ack") && execution.getChallenge() == null) {
                throw new Exception("ackNeeded");
            } else if (!device.getString("tfa").isEmpty() && execution.getChallenge() == null) {
                throw new Exception("pinNeeded");
            } else if (!device.getString("tfa").isEmpty() && execution.getChallenge() != null) {
                String pin = (String) execution.getChallenge().get("pin");
                if (pin != null && !pin.equals(device.getString("tfa"))) {
                    throw new Exception("challengeFailedPinNeeded");
                }
            }
        }
        
        LOGGER.debug("switch execution command MyDataStore line 223");

        switch (execution.command) {
            // action.devices.traits.AppSelector
            case "action.devices.commands.appSelect": {
                String newApplication = (String) execution.getParams().get("newApplication");
                String newApplicationName = (String) execution.getParams().get("newApplicationName");
                String currentApplication = newApplication != null ? newApplication : newApplicationName;
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.currentApplication", currentApplication);
                states.put("currentApplication", currentApplication);
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "currentApplication", currentApplication);
                // ----------------------------------------------
                break;
            }

            case "action.devices.commands.appInstall": {
                String newApplication = (String) execution.getParams().get("newApplication");
                String newApplicationName = (String) execution.getParams().get("newApplicationName");
                String currentApplication = newApplication != null ? newApplication : newApplicationName;
                LOGGER.info("Install app " + currentApplication);
                break;
            }

            case "action.devices.commands.appSearch": {
                String newApplication = (String) execution.getParams().get("newApplication");
                String newApplicationName = (String) execution.getParams().get("newApplicationName");
                String currentApplication = newApplication != null ? newApplication : newApplicationName;
                LOGGER.info("Search for app " + currentApplication);
                break;
            }

            // action.devices.traits.ArmDisarm
            case "action.devices.commands.ArmDisarm":
                if (execution.getParams().containsKey("arm")) {
                    boolean isArmed = (boolean) execution.getParams().get("arm");
                    states.put("isArmed", isArmed);
                } else if (execution.getParams().containsKey("cancel")) {
                    // Cancel value is in relation to the arm value
                    boolean isArmed = (boolean) execution.getParams().get("arm");
                    states.put("isArmed", !isArmed);
                }
                if (execution.getParams().containsKey("armLevel")) {
                    database
                        .collection("users")
                        .document(userId)
                        .collection("devices")
                        .document(deviceId)
                        .update(
                                "states.isArmed",
                                states.get("isArmed"),
                                "states.currentArmLevel",
                                execution.getParams().get("armLevel"));
                    states.put("currentArmLevel", execution.getParams().get("armLevel"));
                } else {
                    database
                        .collection("users")
                        .document(userId)
                        .collection("devices")
                        .document(deviceId)
                        .update("isArmed", states.get("isArmed"));
                }
                break;

            // action.devices.traits.Brightness
            case "action.devices.commands.BrightnessAbsolute":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.brightness", execution.getParams().get("brightness"));
                states.put("brightness", execution.getParams().get("brightness"));
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "brightness", execution.getParams().get("brightness"));
                // ----------------------------------------------
                break;

            // action.devices.traits.CameraStream
            case "action.devices.commands.GetCameraStream":
                states.put("cameraStreamAccessUrl", "https://fluffysheep.com/baaaaa.mp4");
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "cameraStreamAccessUrl", "https://fluffysheep.com/baaaaa.mp4");
                // ----------------------------------------------
                break;

            // action.devices.traits.ColorSetting
            case "action.devices.commands.ColorAbsolute":
                String colorType;
                Object color;
                Map<String, Object> colorMap = (Map<String, Object>) execution.getParams().get("color");

                if (colorMap.containsKey("spectrumRGB")) {
                    database
                        .collection("users")
                        .document(userId)
                        .collection("devices")
                        .document(deviceId)
                        .update("states.color.spectrumRgb", colorMap.get("spectrumRGB"));
                    color = colorMap.get("spectrumRGB");
                    colorType = "spectrumRgb";
                } else {
                    if (colorMap.containsKey("spectrumHSV")) {
                        database
                            .collection("users")
                            .document(userId)
                            .collection("devices")
                            .document(deviceId)
                            .update("states.color.spectrumHsv", colorMap.get("spectrumHSV"));
                        colorType = "spectrumHsv";
                        color = colorMap.get("spectrumHSV");

                    } else {
                        if (colorMap.containsKey("temperature")) {
                            database
                                .collection("users")
                                .document(userId)
                                .collection("devices")
                                .document(deviceId)
                                .update("states.color.temperatureK", colorMap.get("temperature"));
                            colorType = "temperatureK";
                            color = colorMap.get("temperature");

                        } else {
                            throw new Exception("notSupported");
                        }
                    }
                }
                //states.put(colorType, color);
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, colorType, color);
                // ----------------------------------------------
                break;

            // action.devices.traits.Cook
            case "action.devices.commands.Cook":
                boolean startCooking = (boolean) execution.getParams().get("start");
                if (startCooking) {
                    // Start cooking
                    Map<String, Object> dbStates =
                            new HashMap<String, Object>() {
                                {
                                    put("states.currentCookingMode", execution.getParams().get("cookingMode"));
                                }
                            };
                    if (execution.getParams().containsKey("foodPreset")) {
                        dbStates.put("states.currentFoodPreset", execution.getParams().get("foodPreset"));
                    } else {
                        dbStates.put("states.currentFoodPreset", "NONE");
                    }
                    if (execution.getParams().containsKey("quantity")) {
                        dbStates.put("states.currentFoodQuantity", execution.getParams().get("quantity"));
                    } else {
                        dbStates.put("states.currentFoodQuantity", 0);
                    }
                    if (execution.getParams().containsKey("unit")) {
                        dbStates.put("states.currentFoodUnit", execution.getParams().get("unit"));
                    } else {
                        dbStates.put("states.currentFoodUnit", "NONE");
                    }
                    database
                        .collection("users")
                        .document(userId)
                        .collection("devices")
                        .document("deviceId")
                        .update(dbStates);
                    // Server getting response will handle any undefined values
                    states.put("currentCookingMode", execution.getParams().get("cookingMode"));
                    states.put("currentFoodPreset", execution.getParams().get("foodPreset"));
                    states.put("currentFoodQuantity", execution.getParams().get("quantity"));
                    states.put("currentFoodUnit", execution.getParams().get("unit"));
                } else {
                    // Done cooking, reset
                    database
                        .collection("users")
                        .document(userId)
                        .collection("devices")
                        .document("deviceId")
                        .update(
                                new HashMap<String, Object>() {
                                    {
                                        put("states.currentCookingMode", "NONE");
                                        put("states.currentFoodPreset", "NONE");
                                        put("states.currentFoodQuantity", 0);
                                        put("states.currentFoodUnit", "NONE");
                                    }
                                });
                    states.put("currentCookingMode", "NONE");
                    states.put("currentFoodPreset", "NONE");
                }
                publishMqtt(deviceId, "start", execution.getParams().get("start"));
                break;

            case "action.devices.commands.selectChannel":
                // "params":{"channelCode":"cnn","channelName":"CNN","channelNumber":"200"}

                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "channelNumber", execution.getParams().get("channelNumber"));

                break;
            // action.devices.traits.Dispense
            case "action.devices.commands.Dispense":
                int amount = (int) execution.getParams().get("amount");
                String unit = (String) execution.getParams().get("unit");
                if (execution.getParams().containsKey("presetName")
                        && execution.getParams().get("presetName").equals("cat food bowl")) {
                    // Fill in params
                    amount = 4;
                    unit = "CUPS";
                }
                Map<String, Object> amountLastDispensed = new HashMap();
                amountLastDispensed.put("amount", amount);
                amountLastDispensed.put("unit", unit);
                Map<String, Object> dispenseUpdates = new HashMap<>();
                dispenseUpdates.put(
                        "states.dispenseItems",
                        new HashMap[] {
                                new HashMap<String, Object>() {
                                    {
                                        put("itemName", execution.getParams().get("item"));
                                        put("amountLastDispensed", amountLastDispensed);
                                        put("isCurrentlyDispensing", execution.getParams().containsKey("presetName"));
                                    }
                                }
                        });
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update(dispenseUpdates);
                states.put(
                        "dispenseItems",
                        new HashMap[] {
                                new HashMap<String, Object>() {
                                    {
                                        put("itemName", execution.getParams().get("item"));
                                        put("amountLastDispensed", amountLastDispensed);
                                        put("isCurrentlyDispensing", execution.getParams().containsKey("presetName"));
                                    }
                                }
                        });
                break;

            // action.devices.traits.Dock
            case "action.devices.commands.Dock":
                // This has no parameters
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.isDocked", true);
                states.put("isDocked", true);
                break;

            // action.devices.traits.EnergyStorage
            case "action.devices.commands.Charge":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.isCharging", execution.getParams().get("charge"));
                states.put("isCharging", execution.getParams().get("charge"));
                break;

            // action.devices.traits.FanSpeed
            case "action.devices.commands.SetFanSpeed":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.currentFanSpeedSetting", execution.getParams().get("fanSpeed"));
                states.put("currentFanSpeedSetting", execution.getParams().get("fanSpeed"));
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "currentFanSpeedSetting", execution.getParams().get("fanSpeed"));
                // ----------------------------------------------
                break;

            case "action.devices.commands.Reverse":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.currentFanSpeedReverse", true);
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "currentFanSpeedReverse", true);
                // ----------------------------------------------
                break;

            // action.devices.traits.Fill
            case "action.devices.commands.Fill":
                Map<String, Object> updates = new HashMap<>();
                String currentFillLevel = "none";
                boolean fill = (boolean) execution.getParams().get("fill");
                if (fill) {
                    if (execution.getParams().containsKey("fillLevel")) {
                        currentFillLevel = (String) execution.getParams().get("fillLevel");
                    } else {
                        currentFillLevel = "half"; // Default fill level
                    }
                } // Else the device is draining and the fill level is set to "none" by default
                updates.put("states.isFilled", fill);
                updates.put("states.currentFillLevel", currentFillLevel);
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update(updates);
                states.put("isFilled", fill);
                states.put("currentFillLevel", currentFillLevel);
                break;

            // action.devices.traits.HumiditySetting
            case "action.devices.commands.SetHumidity":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update(
                            "states.humiditySetpointPercent",
                            execution.getParams().get("humiditySetpointPercent"));
                states.put("humiditySetpointPercent", execution.getParams().get("humiditySetpointPercent"));
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "humiditySetPointPercent", execution.getParams().get("humiditySetpointPercent"));
                // ----------------------------------------------
                break;

            // action.devices.traits.InputSelector
            case "action.devices.commands.SetInput": {
                String newInput = (String) execution.getParams().get("newInput");
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.currentInput", newInput);
                states.put("currentInput", newInput);
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "currentInput", newInput);
                // ----------------------------------------------
                break;
            }

            case "action.devices.commands.PreviousInput": {
                Map<String, Object> attributes = (Map<String, Object>) device.getData().get("attributes");
                String currentInput = (String) deviceStates.get("currentInput");
                Map<String, Object>[] availableInputs =
                        (Map<String, Object>[]) attributes.get("availableInputs");
                int index = -1;
                for (int i = 0; i < availableInputs.length; i++) {
                    String input = (String) availableInputs[i].get("key");
                    if (currentInput.equals(input)) {
                        index = i;
                    }
                }
                int previousInputIndex = Math.min(index - 1, 0);
                String newInput = (String) availableInputs[previousInputIndex].get("key");

                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.currentInput", newInput);
                states.put("currentInput", newInput);
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "currentInput", newInput);
                // ----------------------------------------------
                break;
            }

            case "action.devices.commands.NextInput": {
                Map<String, Object> attributes = (Map<String, Object>) device.getData().get("attributes");
                String currentInput = (String) deviceStates.get("currentInput");
                Map<String, Object>[] availableInputs =
                        (Map<String, Object>[]) attributes.get("availableInputs");
                int index = -1;
                for (int i = 0; i < availableInputs.length; i++) {
                    String input = (String) availableInputs[i].get("key");
                    if (currentInput.equals(input)) {
                        index = i;
                    }
                }
                int nextInputIndex = Math.min(index + 1, availableInputs.length - 1);
                String newInput = (String) availableInputs[nextInputIndex].get("key");

                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.currentInput", newInput);
                states.put("currentInput", newInput);
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "currentInput", newInput);
                // ----------------------------------------------
                break;
            }

            // action.devices.traits.Locator
            case "action.devices.commands.Locate":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update(
                            "states.silent",
                            execution.getParams().get("silent"),
                            "states.generatedAlert",
                            true);
                states.put("generatedAlert", true);
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "generatedAlert", true);
                // ----------------------------------------------
                break;

            // action.devices.traits.LockUnlock
            case "action.devices.commands.LockUnlock":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.isLocked", execution.getParams().get("lock"));
                states.put("isLocked", execution.getParams().get("lock"));
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "isLocked", execution.getParams().get("lock"));
                // ----------------------------------------------
                break;

            // action.devices.traits.NetworkControl
            case "action.devices.commands.EnableDisableGuestNetwork": {
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.guestNetworkEnabled", execution.getParams().get("enable"));
                states.put("guestNetworkEnabled", execution.getParams().get("enable"));
                break;
            }

            case "action.devices.commands.EnableDisableNetworkProfile": {
                List<String> profiles =
                        (List<String>) ((Map<String, Object>) device.getData().get("attributes")).get("networkProfiles");
                boolean profileExists =
                        profiles.stream()
                            .anyMatch(
                                    (String profile) -> profile.equals(execution.getParams().get("profile")));
                if (!profileExists) {
                    throw new RuntimeException("networkProfileNotRecognized");
                }
                // No state change occurs
                break;
            }

            case "action.devices.commands.TestNetworkSpeed": {
                boolean testDownloadSpeed = (boolean) execution.getParams().get("testDownloadSpeed");
                boolean testUploadSpeed = (boolean) execution.getParams().get("testUploadSpeed");
                Map<String, Object> lastNetworkDownloadSpeedTest =
                        (Map<String, Object>) ((Map<String, Object>) device.getData().get("states"))
                            .get("lastNetworkDownloadSpeedTest");
                Map<String, Object> lastNetworkUploadSpeedTest =
                        (Map<String, Object>) ((Map<String, Object>) device.getData().get("states"))
                            .get("lastNetworkUploadSpeedTest");
                int unixTimestampSec = Math.toIntExact(new Date().getTime() / 1000);
                if (testDownloadSpeed) {
                    lastNetworkDownloadSpeedTest.put("downloadSpeedMbps", (Math.random() * 100));
                    lastNetworkDownloadSpeedTest.put("unixTimestampSec", unixTimestampSec);
                }
                if (testUploadSpeed) {
                    lastNetworkUploadSpeedTest.put("uploadSpeedMbps", (Math.random() * 100));
                    lastNetworkUploadSpeedTest.put("unixTimestampSec", unixTimestampSec);
                }

                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update(
                            "states.lastNetworkDownloadSpeedTest", lastNetworkDownloadSpeedTest,
                            "states.lastNetworkUploadSpeedTest", lastNetworkUploadSpeedTest);
                throw new RuntimeException("PENDING");
            }

            case "action.devices.commands.GetGuestNetworkPassword": {
                states.put("guestNetworkPassword", "wifi-password-123");
            }

            // action.devices.traits.OnOff
            case "action.devices.commands.OnOff":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.on", execution.getParams().get("on"));
                states.put("on", execution.getParams().get("on"));
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "on", execution.getParams().get("on"));
                // ------------------------------------------------
                break;

            // action.devices.traits.OpenClose
            case "action.devices.commands.OpenClose":
                // Check if the device can open in multiple directions
                Map<String, Object> attributes = (Map<String, Object>) device.getData().get("attributes");
                if (attributes != null && attributes.containsKey("openDirection")) {
                    // The device can open in more than one direction
                    String direction = (String) execution.getParams().get("openDirection");
                    List<Map<String, Object>> openStates =
                            (List<Map<String, Object>>) states.get("openState");
                    openStates.forEach(
                            state -> {
                                if (state.get("openDirection").equals(direction)) {
                                    state.put("openPercent", execution.getParams().get("openPercent"));
                                }
                            });
                    states.put("openStates", openStates);
                    database
                        .collection("users")
                        .document(userId)
                        .collection("devices")
                        .document(deviceId)
                        .update("states.openState", openStates);
                    // ------ mqtt sending message to device ----------
                    publishMqtt(deviceId, "openState", openStates);
                    // ----------------------------------------------

                } else {
                    // The device can only open in one direction
                    database
                        .collection("users")
                        .document(userId)
                        .collection("devices")
                        .document(deviceId)
                        .update("states.openPercent", execution.getParams().get("openPercent"));
                    states.put("openPercent", execution.getParams().get("openPercent"));
                    // ------ mqtt sending message to device ----------
                    publishMqtt(deviceId, "openPercent", execution.getParams().get("openPercent"));
                    // ----------------------------------------------
                }
                break;

            // action.devices.traits.Reboot
            case "action.devices.commands.Reboot":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.online", false);
                break;

            // action.devices.traits.Rotation
            case "action.devices.commands.RotateAbsolute":
                // Check if the device can open in multiple directions
                if (execution.getParams().containsKey("rotationPercent")) {
                    database
                        .collection("users")
                        .document(userId)
                        .collection("devices")
                        .document(deviceId)
                        .update("states.rotationPercent", execution.getParams().get("rotationPercent"));
                    states.put("rotationPercent", execution.getParams().get("rotationPercent"));
                    // ------ mqtt sending message to device ----------
                    publishMqtt(deviceId, "rotationPercent", execution.getParams().get("rotationPercent"));
                    // ----------------------------------------------
                } else if (execution.getParams().containsKey("rotationDegrees")) {
                    database
                        .collection("users")
                        .document(userId)
                        .collection("devices")
                        .document(deviceId)
                        .update("states.rotationDegrees", execution.getParams().get("rotationDegrees"));
                    states.put("rotationDegrees", execution.getParams().get("rotationDegrees"));
                    // ------ mqtt sending message to device ----------
                    publishMqtt(deviceId, "rotationDegrees", execution.getParams().get("rotationDegrees"));
                    // ----------------------------------------------
                }
                break;

            // action.devices.traits.RunCycle - No execution
            // action.devices.traits.Scene
            case "action.devices.commands.ActivateScene":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.deactivate", execution.getParams().get("deactivate"));
                // Scenes are stateless
                break;

            // action.devices.traits.SoftwareUpdate
            case "action.devices.commands.SoftwareUpdate":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update(
                            new HashMap<String, Object>() {
                                {
                                    put("states.online", false);
                                    put("states.lastSoftwareUpdateUnixTimestampSec", new Date().getTime() / 1000);
                                }
                            });
                break;

            // action.devices.traits.StartStop
            case "action.devices.commands.StartStop":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.isRunning", execution.getParams().get("start"));
                states.put("isRunning", execution.getParams().get("start"));
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "isRunning", execution.getParams().get("start"));
                // ----------------------------------------------
                break;

            case "action.devices.commands.PauseUnpause":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.isPaused", execution.getParams().get("pause"));
                states.put("isPaused", execution.getParams().get("pause"));
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "isPaused", execution.getParams().get("pause"));
                // ----------------------------------------------
                break;

            // action.devices.traits.Modes
            case "action.devices.commands.SetModes":
                Map<String, Object> currentModeSettings =
                        (Map<String, Object>) states.getOrDefault("currentModeSettings", new HashMap<String, Object>());
                currentModeSettings.putAll(
                        (Map<String, Object>) execution
                            .getParams()
                            .getOrDefault("updateModeSettings", new HashMap<String, Object>()));
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.currentModeSettings", currentModeSettings);
                states.put("currentModeSettings", currentModeSettings);
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "currentModeSettings", currentModeSettings);
                // ----------------------------------------------
                break;

            // action.devices.traits.Timer
            case "action.devices.commands.TimerStart":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.timerRemainingSec", execution.getParams().get("timerTimeSec"));
                states.put("timerRemainingSec", execution.getParams().get("timerTimeSec"));
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "timerRemainingSec", execution.getParams().get("timerTimeSec"));
                // ----------------------------------------------
                break;

            case "action.devices.commands.TimerAdjust":
                if ((int) states.get("timerRemainingSec") == -1) {
                    // No timer exists
                    throw new RuntimeException("noTimerExists");
                }
                int newTimerRemainingSec =
                        (int) states.get("timerRemainingSec") + (int) execution.getParams().get("timerTimeSec");
                if (newTimerRemainingSec < 0) {
                    throw new RuntimeException("valueOutOfRange");
                }
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.timerRemainingSec", newTimerRemainingSec);
                states.put("timerRemainingSec", newTimerRemainingSec);
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "timerRemainingSec", newTimerRemainingSec);
                // ----------------------------------------------
                break;

            case "action.devices.commands.TimerPause":
                if ((int) states.get("timerRemainingSec") == -1) {
                    // No timer exists
                    throw new RuntimeException("noTimerExists");
                }
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.timerPaused", true);
                states.put("timerPaused", true);
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "timerPaused", true);
                // ----------------------------------------------
                break;

            case "action.devices.commands.TimerResume":
                if ((int) states.get("timerRemainingSec") == -1) {
                    // No timer exists
                    throw new RuntimeException("noTimerExists");
                }
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.timerPaused", false);
                states.put("timerPaused", false);
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "timerPaused", false);
                // ----------------------------------------------
                break;

            case "action.devices.commands.TimerCancel":
                if ((int) states.get("timerRemainingSec") == -1) {
                    // No timer exists
                    throw new RuntimeException("noTimerExists");
                }
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.timerRemainingSec", -1);
                states.put("timerRemainingSec", 0);
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "timerRemainingSec", 0);
                // ----------------------------------------------
                break;

            // action.devices.traits.Toggles
            case "action.devices.commands.SetToggles":
                Map<String, Object> currentToggleSettings =
                        (Map<String, Object>) states.getOrDefault("currentToggleSettings", new HashMap<String, Object>());
                currentToggleSettings.putAll(
                        (Map<String, Object>) execution
                            .getParams()
                            .getOrDefault("updateToggleSettings", new HashMap<String, Object>()));
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.currentToggleSettings", currentToggleSettings);
                states.put("currentToggleSettings", currentToggleSettings);
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "currentToggleSettings", currentToggleSettings);
                // ----------------------------------------------
                break;

            // action.devices.traits.TemperatureControl
            case "action.devices.commands.SetTemperature":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.temperatureSetpointCelsius", execution.getParams().get("temperature"));
                states.put("temperatureSetpointCelsius", execution.getParams().get("temperature"));
                states.put("temperatureAmbientCelsius", deviceStates.get("temperatureAmbientCelsius"));
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "temperatureSetpointCelsius", execution.getParams().get("temperature"));
                // ----------------------------------------------
                break;

            // action.devices.traits.TemperatureSetting
            case "action.devices.commands.ThermostatTemperatureSetpoint":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update(
                            "states.thermostatTemperatureSetpoint",
                            execution.getParams().get("thermostatTemperatureSetpoint"));
                states.put(
                        "thermostatTemperatureSetpoint",
                        execution.getParams().get("thermostatTemperatureSetpoint"));
                states.put("thermostatMode", deviceStates.get("states.thermostatMode"));
                states.put(
                        "thermostatTemperatureAmbient", deviceStates.get("thermostatTemperatureAmbient"));
                states.put("thermostatHumidityAmbient", deviceStates.get("thermostatHumidityAmbient"));
                if (states.containsKey("online")) {
                    states.remove("online");
                }
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "thermostatTemperatureSetpoint", execution.getParams().get("thermostatTemperatureSetpoint"));
                // ----------------------------------------------
                break;

            case "action.devices.commands.ThermostatTemperatureSetRange":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update(
                            "states.thermostatTemperatureSetpointLow",
                            execution.getParams().get("thermostatTemperatureSetpointLow"),
                            "states.thermostatTemperatureSetpointHigh",
                            execution.getParams().get("thermostatTemperatureSetpointHigh"));
                states.put(
                        "thermostatTemperatureSetpoint", deviceStates.get("thermostatTemperatureSetpoint"));
                states.put("thermostatMode", deviceStates.get("thermostatMode"));
                states.put(
                        "thermostatTemperatureAmbient", deviceStates.get("thermostatTemperatureAmbient"));
                states.put("thermostatHumidityAmbient", deviceStates.get("thermostatHumidityAmbient"));
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "thermostatTemperatureSetpointLow", execution.getParams().get("thermostatTemperatureSetpointLow"));
                // ----------------------------------------------
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "thermostatTemperatureSetpointHigh", execution.getParams().get("thermostatTemperatureSetpointHigh"));
                // ----------------------------------------------
                break;

            case "action.devices.commands.ThermostatSetMode":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.thermostatMode", execution.getParams().get("thermostatMode"));
                states.put("thermostatMode", execution.getParams().get("thermostatMode"));
                states.put(
                        "thermostatTemperatureSetpoint", deviceStates.get("thermostatTemperatureSetpoint"));
                states.put(
                        "thermostatTemperatureAmbient", deviceStates.get("thermostatTemperatureAmbient"));
                states.put("thermostatHumidityAmbient", deviceStates.get("thermostatHumidityAmbient"));
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "thermostatMode", execution.getParams().get("thermostatMode"));
                // ----------------------------------------------
                break;

            // action.devices.traits.TransportControl
            // Traits are considered no-ops as they have no state
            case "action.devices.commands.mediaPrevious":
                LOGGER.info("Play the previous media");
                break;

            case "action.devices.commands.mediaNext":
                LOGGER.info("Play the next media");
                break;

            case "action.devices.commands.mediaRepeatMode":
                Boolean isOn = (Boolean) execution.getParams().get("isOn");
                Boolean isSingle = (Boolean) execution.getParams().get("isSingle");
                LOGGER.info("Repeat mode enabled: " + isOn + ". Single item enabled: " + isSingle);
                break;

            case "action.devices.commands.mediaShuffle":
                LOGGER.info("Shuffle the playlist of media");
                break;

            case "action.devices.commands.mediaClosedCaptioningOn":
                String ccLanguage = (String) execution.getParams().get("closedCaptioningLanguage");
                String uqLanguage = (String) execution.getParams().get("userQueryLanguage");
                LOGGER.info("Closed captioning enabled for " + ccLanguage + " for user in " + uqLanguage);
                break;

            case "action.devices.commands.mediaClosedCaptioningOff":
                LOGGER.info("Closed captioning disabled");
                break;

            case "action.devices.commands.mediaPause":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.playbackState", "PAUSED");
                states.put("playbackState", "PAUSED");
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "playbackState", "PAUSED");
                // ----------------------------------------------
                break;

            case "action.devices.commands.mediaResume":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.playbackState", "PLAYING");
                states.put("playbackState", "PLAYING");
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "playbackState", "PLAYING");
                // ----------------------------------------------
                break;

            case "action.devices.commands.mediaStop":
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.playbackState", "STOPPED");
                states.put("playbackState", "STOPPED");
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "playbackState", "STOPPED");
                // ----------------------------------------------
                break;

            case "action.devices.commands.mediaSeekRelative":
                int relativePositionMs = (int) execution.getParams().get("relativePositionMs");
                LOGGER.info("Seek to (now + " + relativePositionMs + ") ms");
                break;

            case "action.devices.commands.mediaSeekToPosition":
                int absPositionMs = (int) execution.getParams().get("absPositionMs");
                LOGGER.info("Seek to " + absPositionMs + " ms");
                break;

            // action.devices.traits.Volume
            case "action.devices.commands.setVolume":
                int volumeLevel = (int) execution.getParams().get("volumeLevel");
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.currentVolume", volumeLevel);
                states.put("currentVolume", volumeLevel);
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "currentVolume", volumeLevel);
                // ----------------------------------------------
                break;

            case "action.devices.commands.volumeRelative":
                int relativeSteps = Integer.valueOf(execution.getParams().get("relativeSteps").toString());
                int currentVolume = new Double(deviceStates.get("currentVolume").toString()).intValue();
                int newVolume = currentVolume + relativeSteps;
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.currentVolume", newVolume);
                states.put("currentVolume", newVolume);
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "currentVolume", newVolume);
                // -----------------------------------------------
                break;

            case "action.devices.commands.mute":
                boolean mute = (boolean) execution.getParams().get("mute");
                database
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(deviceId)
                    .update("states.isMuted", mute);
                states.put("isMuted", mute);
                // ------ mqtt sending message to device ----------
                publishMqtt(deviceId, "isMuted", mute);
                // -----------------------------------------------
                break;
        }

        return states;
    }

    private void publishMqtt(String topic, String key, Object value) {
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> myState = new HashMap<>();
                myState.put(key, value);
                JSONObject json = new JSONObject(myState);
                String msg = json.toString();
                mqtt.publish(topic + "-client", 0, msg.getBytes());
                LOGGER.debug("Message = " + msg + " sent by MQTT from MyDataStore");
            } catch (Throwable throwable) {
                LOGGER.error("failed to publish iot device: {" + topic + "}", throwable);
            }
        });
    }
}
