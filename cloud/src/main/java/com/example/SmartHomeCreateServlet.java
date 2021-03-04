/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.actions.api.smarthome.SmartHomeApp;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.Gson;

/**
 * Handles request received via HTTP POST and delegates it to your Actions app. See: [Request
 * handling in Google App
 * Engine](https://cloud.google.com/appengine/docs/standard/java/how-requests-are-handled).
 */
@WebServlet(name = "smarthomeCreate", urlPatterns = "/smarthome/create")
public class SmartHomeCreateServlet extends HttpServlet {
  private static final Logger LOGGER = LoggerFactory.getLogger(MySmartHomeApp.class);
  private static MyDataStore database = MyDataStore.getInstance();

  // Setup creds for requestSync
  private final SmartHomeApp actionsApp = new MySmartHomeApp();

  {
    try {
      GoogleCredentials credentials =
          GoogleCredentials.fromStream(getClass().getResourceAsStream("/smart-home-key.json"));
      actionsApp.setCredentials(credentials);
    } catch (Exception e) {
      LOGGER.error("couldn't load credentials");
    }
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
    String body = req.getReader().lines().collect(Collectors.joining());
    LOGGER.info("doPost, body = {}", body);
    Map<String, Object> device = new Gson().fromJson(body, HashMap.class);

    String userId = (String) device.get("userId");
    Map<String, Object> deviceData = (Map<String, Object>) device.get("data");

    try {
      database.addDevice(userId, deviceData);
    } catch (Exception e) {
      LOGGER.error("adding device failed: {}", e);
      res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      res.setHeader("Access-Control-Allow-Origin", "*");
      res.setContentType("text/plain");
      res.getWriter().println("ERROR");
      return;
    }

    try {
      actionsApp.requestSync(userId);
    } catch (Exception e) {
      LOGGER.error("request sync failed: {}", e);
    }

    res.setStatus(HttpServletResponse.SC_OK);
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setContentType("text/plain");
    res.getWriter().println("OK");
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
    res.setContentType("text/plain");
    res.getWriter().println("/smarthome/create is a POST call");
  }

  @Override
  protected void doOptions(HttpServletRequest req, HttpServletResponse res) {
    // pre-flight request processing
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
    res.setHeader("Access-Control-Allow-Headers", "X-Requested-With,Content-Type,Accept,Origin");
  }
}
