// WebHtmlBuilder.java
package com.ezhan.amr.web;

import android.content.Context;
import androidx.annotation.StringRes;

import com.ezhan.amr.R;
import com.ezhan.amr.utils.LocaleHelper;

public class WebHtmlBuilder {
    private final Context context;
    private final int workStatus;
    private final int batteryLevel;
    private final Boolean emergency;
    private final String targetId;
    private final int confidence;
    private final String pos;
    private final String errorMsg;
    private final String operation;

    public WebHtmlBuilder(Context context, int workStatus, int batteryLevel,
                          Boolean emergency, String targetId, int confidence,
                          String pos, String errorMsg, String operation) {
        this.context = context;
        this.workStatus = workStatus;
        this.batteryLevel = batteryLevel;
        this.emergency = emergency;
        this.targetId = targetId;
        this.confidence = confidence;
        this.pos = pos;
        this.errorMsg = errorMsg;
        this.operation = operation;
    }

    public String buildMainHtml(String stationOptions, String jackTaskOptions) {
        String webTitle = getLocalizedString(R.string.web_title);
        String statusTitle = getLocalizedString(R.string.robot_status_title);
        String selectStationHint = getLocalizedString(R.string.select_station_hint);
        String selectTaskHint = getLocalizedString(R.string.select_jack_task_hint);
        String btnConfirm = getLocalizedString(R.string.btn_confirm);
        String btnPark = getLocalizedString(R.string.btn_park);
        String btnCharge = getLocalizedString(R.string.btn_charge);
        String btnCancel = getLocalizedString(R.string.btn_cancel);
        String btnPause = getLocalizedString(R.string.btn_pause);
        String btnResume = getLocalizedString(R.string.btn_resume);

        // 状态卡片标签
        String statusRunning = getLocalizedString(R.string.status_running);
        String statusBattery = getLocalizedString(R.string.status_battery);
        String statusEmergency = getLocalizedString(R.string.status_emergency);
        String statusTarget = getLocalizedString(R.string.status_target);
        String statusConfidence = getLocalizedString(R.string.status_confidence);
        String statusPosition = getLocalizedString(R.string.status_position);
        String statusOperation = getLocalizedString(R.string.status_operation);
        String statusAlert = getLocalizedString(R.string.status_alert);

        // 实时获取状态字符串
        String stateStr = getStateString(workStatus);

        // 构建状态显示项
        String statusDisplay = "<div class='status-grid' id='status-grid'>" +
                buildStatusCard("🚀",  statusRunning, "<span id='state_value'>" + stateStr + "</span>", getStateColor(stateStr), "state") +
                buildStatusCard("🔋", statusBattery, "<span id='battery_value'>" + batteryLevel + "</span>", getBatteryColor(batteryLevel), "battery") +
                buildStatusCard(emergency ? "🚨" : "✅", statusEmergency, "<span id='emergency_value'>" + (emergency ? LocaleHelper.onServiceGetString(context, R.string.emergency_activated) : LocaleHelper.onServiceGetString(context, R.string.status_normal)) + "</span>", emergency ? "#F44336" : "#4CAF50", "emergency") +
                buildStatusCard("🎯", statusTarget, "<span id='target_value'>" + (targetId.isEmpty() ? LocaleHelper.onServiceGetString(context, R.string.no_target) : targetId.replace(LocaleHelper.onServiceGetString(context, R.string.target_prefix), "")) + "</span>", "#673AB7", "target") +
                buildStatusCard("📊", statusConfidence, "<span id='confidence_value'>" + confidence + "</span>", getConfidenceColor(confidence), "confidence") +
                buildStatusCard("📍", statusPosition, "<span id='position_value'>" + pos + "</span>", "#607D8B", "position") +
                buildStatusCard("⚙️", statusOperation, "<span id='operation_value'>" + operation + "</span>", "#2196F3", "operation") +
                buildStatusCard("⚠️", statusAlert, "<span id='errorMsg_value'>" + errorMsg + "</span>",
                        !LocaleHelper.onServiceGetString(context, R.string.no_error).equals(errorMsg) ? "#F44336" : "#4CAF50", "errorMsg") +
                "</div>";

        return "<!DOCTYPE html>" +
                "<html lang='zh-CN'>" +
                "<head>" +
                "  <meta charset='UTF-8'>" +
                "  <meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "  <title>" + webTitle + "</title>" +
                "  <link href='https://fonts.googleapis.com/css2?family=Noto+Sans+SC:wght@400;500;700&display=swap' rel='stylesheet'>" +
                "  <script>" +
                "    const NO_ERROR_MSG = '" + LocaleHelper.onServiceGetString(context, R.string.no_error) + "';" +
                "    function updateStatus(data) {" +
                "      if (data.state) document.getElementById('state_value').textContent = data.state;" +
                "      if (data.batteryLevel) document.getElementById('battery_value').textContent = data.batteryLevel + '%';" +
                "      if (data.targetId) document.getElementById('target_value').textContent = data.targetId;" +
                "      if (data.confidence) document.getElementById('confidence_value').textContent = data.confidence + '%';" +
                "      if (data.pos) document.getElementById('position_value').textContent = data.pos;" +
                "      if (data.operation) document.getElementById('operation_value').textContent = data.operation;" +
                "      if (data.errorMsg) {" +
                "        const errorElement = document.getElementById('errorMsg_value');" +
                "        errorElement.textContent = data.errorMsg;" +
                "      }" +
                "      if (data.emergency !== undefined) {" +
                "        document.getElementById('emergency_value').textContent = data.emergency ? '" + LocaleHelper.onServiceGetString(context, R.string.emergency_activated) + "' : '" + LocaleHelper.onServiceGetString(context, R.string.status_normal) + "';" +
                "        document.getElementById('safety_icon').textContent = data.emergency ? '🚨' : '✅';" +
                "      }" +
                "      updateCardColors(data);" +
                "    }" +
                "    " +
                "    function updateCardColors(data) {" +
                "      updateCardColor('state', data.state ? getStateColor(data.state) : null);" +
                "      updateCardColor('battery', data.batteryLevel ? getBatteryColor(data.batteryLevel) : null);" +
                "      updateCardColor('confidence', data.confidence ? getConfidenceColor(data.confidence) : null);" +
                "      updateCardColor('emergency', data.emergency !== undefined ? (data.emergency ? '#F44336' : '#4CAF50') : null);" +
                "      updateCardColor('errorMsg', data.errorMsg ? (data.errorMsg !== NO_ERROR_MSG ? '#F44336' : '#4CAF50') : null);" +
                "    }" +
                "    " +
                "    function updateCardColor(type, color) {" +
                "      if (color === null) return;" +
                "      const card = document.querySelector(\".status-card[data-type='\" + type + \"']\");" +
                "      if (card) {" +
                "        const icon = card.querySelector('.status-icon');" +
                "        if (icon) {" +
                "          icon.style.backgroundColor = color;" +
                "        }" +
                "      }" +
                "    }" +
                "    " +
                "    function getStateColor(state) {" +
                "      switch(state) {" +
                "        case '" + LocaleHelper.onServiceGetString(context, R.string.state_idle) + "': return '#4CAF50';" +
                "        case '" + LocaleHelper.onServiceGetString(context, R.string.state_running) + "': return '#2196F3';" +
                "        case '" + LocaleHelper.onServiceGetString(context, R.string.state_failed) + "': return '#F44336';" +
                "        case '" + LocaleHelper.onServiceGetString(context, R.string.state_completed) + "': return '#4CAF50';" +
                "        default: return '#673AB7';" +
                "      }" +
                "    }" +
                "    " +
                "    function getBatteryColor(level) {" +
                "      return level >= 80 ? '#4CAF50' :" +
                "             level >= 50 ? '#8BC34A' :" +
                "             level >= 20 ? '#FFC107' : '#F44336';" +
                "    }" +
                "    " +
                "    function getConfidenceColor(confidence) {" +
                "      return confidence >= 70 ? '#4CAF50' :" +
                "             confidence >= 40 ? '#FFC107' : '#F44336';" +
                "    }" +
                "    " +
                "    function fetchStatus() {" +
                "      fetch('/api/status?t=' + Date.now())" +
                "        .then(response => {" +
                "          if (!response.ok) throw new Error('" + LocaleHelper.onServiceGetString(context, R.string.error_network_response) + "');" +
                "          return response.json();" +
                "        })" +
                "        .then(data => {" +
                "          updateStatus(data);" +
                "          setTimeout(fetchStatus, 1000);" +
                "        })" +
                "        .catch(error => {" +
                "          console.error('" + LocaleHelper.onServiceGetString(context, R.string.error_fetch_status) + ":', error);" +
                "          setTimeout(fetchStatus, 5000);" +
                "        });" +
                "    }" +
                "    " +
                "    document.addEventListener('DOMContentLoaded', function() {" +
                "      const initialState = {" +
                "        state: '" + stateStr + "'," +
                "        batteryLevel: " + batteryLevel + "," +
                "        targetId: '" + (targetId.isEmpty() ? LocaleHelper.onServiceGetString(context, R.string.no_target) : targetId.replace(LocaleHelper.onServiceGetString(context, R.string.target_prefix), "")) + "'," +
                "        confidence: " + confidence + "," +
                "        pos: '" + pos + "'," +
                "        operation: '" + operation + "'," +
                "        emergency: " + emergency + "," +
                "        errorMsg: '" + errorMsg + "'" +
                "      };" +
                "      updateStatus(initialState);" +
                "      fetchStatus();" +
                "    });" +
                "    " +
                "    function validateSelection() {" +
                "      const select = document.getElementById('stationSelect');" +
                "      if (!select.value) {" +
                "        alert('" + LocaleHelper.onServiceGetString(context, R.string.alert_select_station) + "');" +
                "        return false;" +
                "      }" +
                "      return true;" +
                "    }" +
                "    " +
                "    function validateTaskSelection() {" +
                "      const select = document.getElementById('stationJackTask');" +
                "      if (!select.value) {" +
                "        alert('" + LocaleHelper.onServiceGetString(context, R.string.alert_select_task) + "');" +
                "        return false;" +
                "      }" +
                "      return true;" +
                "    }" +
                "    function sendCommand(cmd) {" +
                "      fetch('/?call=' + cmd, {" +
                "        method: 'GET'" +
                "      })" +
                "      .then(response => {" +
                "        if (response.ok) {" +
                "          console.log('" + LocaleHelper.onServiceGetString(context, R.string.log_command_sent) + " ' + cmd);" +
                "        } else {" +
                "          console.error('" + LocaleHelper.onServiceGetString(context, R.string.error_send_command) + "');" +
                "        }" +
                "      })" +
                "      .catch(error => {" +
                "        console.error('" + LocaleHelper.onServiceGetString(context, R.string.error_send_command) + ":', error);" +
                "      });" +
                "    }" +
                "    function handleFormSubmit(event, formId) {" +
                "      event.preventDefault();" +
                "      const form = document.getElementById(formId);" +
                "      const formData = new FormData(form);" +
                "      const params = new URLSearchParams(formData).toString();" +
                "      " +
                "      fetch('/?' + params, {" +
                "        method: 'GET'" +
                "      })" +
                "      .then(response => {" +
                "        if (response.ok) {" +
                "          console.log('" + LocaleHelper.onServiceGetString(context, R.string.log_form_submitted) + "');" +
                "        } else {" +
                "          console.error('" + LocaleHelper.onServiceGetString(context, R.string.error_form_submit) + "');" +
                "        }" +
                "      })" +
                "      .catch(error => {" +
                "        console.error('" + LocaleHelper.onServiceGetString(context, R.string.error_form_submit) + ":', error);" +
                "      });" +
                "    }" +
                "  </script>" +
                "  <style>" +
                "    :root {\n" +
                "      --primary-color: #4361ee;\n" +
                "      --primary-hover: #3a56d4;\n" +
                "      --success-color: #4cc9f0;\n" +
                "      --success-hover: #3db5d6;\n" +
                "      --warning-color: #f8961e;\n" +
                "      --warning-hover: #e0851b;\n" +
                "      --danger-color: #f94144;\n" +
                "      --danger-hover: #e0383b;\n" +
                "      --dark-color: #2b2d42;\n" +
                "      --light-color: #f8f9fa;\n" +
                "      --border-radius: 12px;\n" +
                "      --box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);\n" +
                "      --transition: all 0.25s cubic-bezier(0.645, 0.045, 0.355, 1);\n" +
                "    }\n" +
                "    * {\n" +
                "      box-sizing: border-box;\n" +
                "      margin: 0;\n" +
                "      padding: 0;\n" +
                "    }\n" +
                "    body {\n" +
                "      font-family: 'Noto Sans SC', system-ui, sans-serif;\n" +
                "      line-height: 1.6;\n" +
                "      background: linear-gradient(135deg, #f5f7fa 0%, #e6e9f0 100%);\n" +
                "      min-height: 100vh;\n" +
                "      padding: 2rem;\n" +
                "      color: var(--dark-color);\n" +
                "    }\n" +
                "    .container {\n" +
                "      max-width: 640px;\n" +
                "      margin: 0 auto;\n" +
                "      background: rgba(255, 255, 255, 0.95);\n" +
                "      border-radius: var(--border-radius);\n" +
                "      padding: 2.5rem;\n" +
                "      box-shadow: var(--box-shadow);\n" +
                "      backdrop-filter: blur(8px);\n" +
                "      border: 1px solid rgba(255, 255, 255, 0.2);\n" +
                "    }\n" +
                "    h1 {\n" +
                "      color: var(--primary-color);\n" +
                "      margin-bottom: 2rem;\n" +
                "      font-weight: 700;\n" +
                "      text-align: center;\n" +
                "      font-size: 1.8rem;\n" +
                "      letter-spacing: 0.5px;\n" +
                "      position: relative;\n" +
                "      padding-bottom: 1rem;\n" +
                "    }\n" +
                "    h1::after {\n" +
                "      content: '';\n" +
                "      position: absolute;\n" +
                "      bottom: 0;\n" +
                "      left: 50%;\n" +
                "      transform: translateX(-50%);\n" +
                "      width: 80px;\n" +
                "      height: 3px;\n" +
                "      background: linear-gradient(90deg, var(--primary-color), var(--success-color));\n" +
                "      border-radius: 3px;\n" +
                "    }\n" +
                "    .status-box {\n" +
                "      background: white;\n" +
                "      padding: 1.5rem;\n" +
                "      border-radius: var(--border-radius);\n" +
                "      margin-bottom: 1.5rem;\n" +
                "      box-shadow: 0 2px 10px rgba(0, 0, 0, 0.05);\n" +
                "      transition: var(--transition);\n" +
                "    }\n" +
                "    .status-box:hover {\n" +
                "      transform: translateY(-2px);\n" +
                "      box-shadow: 0 6px 15px rgba(0, 0, 0, 0.1);\n" +
                "    }\n" +
                "    .status-title {\n" +
                "      font-weight: 600;\n" +
                "      color: var(--dark-color);\n" +
                "      margin-bottom: 0.75rem;\n" +
                "      font-size: 1rem;\n" +
                "      display: flex;\n" +
                "      align-items: center;\n" +
                "    }\n" +
                "    .status-icon {" +
                "    transition: background-color 0.5s ease;" +
                "    }" +
                "    .error-message {" +
                "        transition: all 0.5s ease;" +
                "    }" +
                "    .status-title::before {\n" +
                "      content: '';\n" +
                "      display: inline-block;\n" +
                "      width: 6px;\n" +
                "      height: 6px;\n" +
                "      background: var(--primary-color);\n" +
                "      border-radius: 50%;\n" +
                "      margin-right: 8px;\n" +
                "    }\n" +
                "    .status-value {\n" +
                "      font-size: 1.15rem;\n" +
                "      color: var(--dark-color);\n" +
                "      font-weight: 500;\n" +
                "      padding-left: 14px;\n" +
                "    }\n" +
                "    .status-grid {" +
                "      display: grid;" +
                "      grid-template-columns: repeat(2, 1fr);" +
                "      gap: 0.8rem;" +
                "    }" +
                "    .status-card {" +
                "      background: white;" +
                "      border-radius: var(--border-radius);" +
                "      padding: 0.8rem;" +
                "      display: flex;" +
                "      align-items: center;" +
                "      gap: 0.8rem;" +
                "      box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);" +
                "      transition: all 0.3s ease;" +
                "      border-left: 3px solid transparent;" +
                "    }" +
                "    .status-icon {" +
                "      width: 36px;" +
                "      height: 36px;" +
                "      font-size: 1rem;" +
                "    }" +
                "    .status-label {" +
                "      font-size: 0.8rem;" +
                "    }" +
                "    .status-value {" +
                "      font-size: 0.9rem;" +
                "    }" +
                "    .btn-container {\n" +
                "      display: grid;\n" +
                "      gap: 1.25rem;\n" +
                "    }\n" +
                "    .btn-row {" +
                "      display: flex;" +
                "      width: 100%;" +
                "      gap: 0.5rem;" +
                "      margin-bottom: 0.75rem;" +
                "    }" +
                "    .btn-row a {" +
                "      flex: 1;" +
                "      min-width: 0;" +
                "    }" +
                "    .btn-row button {" +
                "      width: 100%;" +
                "      padding: 1rem;" +
                "    }" +
                "    .form-row {" +
                "      display: flex;" +
                "      width: 100%;" +
                "      gap: 0.5rem;" +
                "      margin-bottom: 1rem;" +
                "    }" +
                "    .form-row select {\n" +
                "      flex: 3;\n" +
                "    }\n" +
                "    .form-row button {\n" +
                "      flex: 1;\n" +
                "    }\n" +
                "    button, select {\n" +
                "      padding: 1.1rem 1.5rem;\n" +
                "      border: none;\n" +
                "      border-radius: var(--border-radius);\n" +
                "      font-size: 1rem;\n" +
                "      font-weight: 600;\n" +
                "      cursor: pointer;\n" +
                "      transition: var(--transition);\n" +
                "      display: flex;\n" +
                "      align-items: center;\n" +
                "      justify-content: center;\n" +
                "      gap: 0.5rem;\n" +
                "    }\n" +
                "    select {\n" +
                "      background-color: white;\n" +
                "      border: 1px solid #e0e3e8;\n" +
                "      appearance: none;\n" +
                "      background-image: url(\"data:image/svg+xml;charset=UTF-8,%3csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3e%3cpolyline points='6 9 12 15 18 9'%3e%3c/polyline%3e%3c/svg%3e\");\n" +
                "      background-repeat: no-repeat;\n" +
                "      background-position: right 1rem center;\n" +
                "      background-size: 1em;\n" +
                "    }\n" +
                "    select:focus {\n" +
                "      outline: none;\n" +
                "      border-color: var(--primary-color);\n" +
                "      box-shadow: 0 0 0 3px rgba(67, 97, 238, 0.2);\n" +
                "    }\n" +
                "    a {\n" +
                "      text-decoration: none;\n" +
                "    }\n" +
                "    .btn-primary {\n" +
                "      background-color: var(--primary-color);\n" +
                "      color: white;\n" +
                "    }\n" +
                "    .btn-primary:hover {\n" +
                "      background-color: var(--primary-hover);\n" +
                "    }\n" +
                "    .btn-success {\n" +
                "      background-color: var(--success-color);\n" +
                "      color: white;\n" +
                "    }\n" +
                "    .btn-success:hover {\n" +
                "      background-color: var(--success-hover);\n" +
                "    }\n" +
                "    .btn-warning {\n" +
                "      background-color: var(--warning-color);\n" +
                "      color: white;\n" +
                "    }\n" +
                "    .btn-warning:hover {\n" +
                "      background-color: var(--warning-hover);\n" +
                "    }\n" +
                "    .btn-danger {\n" +
                "      background-color: var(--danger-color);\n" +
                "      color: white;\n" +
                "    }\n" +
                "    .btn-danger:hover {\n" +
                "      background-color: var(--danger-hover);\n" +
                "    }\n" +
                "    button:hover {\n" +
                "      transform: translateY(-3px);\n" +
                "      box-shadow: 0 8px 20px rgba(0, 0, 0, 0.15);\n" +
                "    }\n" +
                "    button:active {\n" +
                "      transform: translateY(0);\n" +
                "    }\n" +
                "    .error-message {\n" +
                "      color: var(--danger-color);\n" +
                "      background-color: rgba(249, 65, 68, 0.1);\n" +
                "      padding: 1.25rem;\n" +
                "      border-radius: var(--border-radius);\n" +
                "      margin-bottom: 1.5rem;\n" +
                "      border-left: 4px solid var(--danger-color);\n" +
                "      animation: fadeIn 0.3s ease-out;\n" +
                "    }\n" +
                "    @keyframes fadeIn {\n" +
                "      from { opacity: 0; transform: translateY(-10px); }\n" +
                "      to { opacity: 1; transform: translateY(0); }\n" +
                "    }\n" +
                "    @media (max-width: 600px) {\n" +
                "      body {\n" +
                "        padding: 1rem;\n" +
                "      }\n" +
                "      .container {\n" +
                "        padding: 1.5rem;\n" +
                "      }\n" +
                "      .status-grid {\n" +
                "        grid-template-columns: repeat(2, 1fr);\n" +
                "      }\n" +
                "      .form-row, .btn-row {\n" +
                "        flex-direction: column;\n" +
                "      }\n" +
                "      select, button {\n" +
                "        width: 100%;\n" +
                "      }\n" +
                "    }\n" +
                "    @media (max-width: 400px) {\n" +
                "      .status-grid {\n" +
                "        grid-template-columns: 1fr;\n" +
                "      }\n" +
                "    }\n" +
                ".status-icon-container {\n" +
                "   display: flex;\n" +
                "   align-items: center;\n" +
                "   justify-content: center;\n" +
                "   width: 40px;\n" +
                "   height: 40px;\n" +
                "   flex-shrink: 0;\n" +
                "}\n" +
                ".status-icon {\n" +
                "   width: 36px;\n" +
                "   height: 36px;\n" +
                "   border-radius: 50%;\n" +
                "   display: flex;\n" +
                "   align-items: center;\n" +
                "   justify-content: center;\n" +
                "   font-size: 1rem;\n" +
                "   color: white;\n" +
                "}\n" +
                "    .btn-row {" +
                "      display: flex;" +
                "      gap: 0.75rem;" +
                "      margin-bottom: 0.75rem;" +
                "    }" +
                "    .btn-row button {" +
                "      flex: 1;" +
                "      min-width: 0;" +
                "    }" +
                "    .form-row {" +
                "      display: flex;" +
                "      gap: 0.75rem;" +
                "      margin-bottom: 1.25rem;" +
                "    }" +
                "    .queue-box {" +
                "      background: #f8f9fa;" +
                "      border-radius: var(--border-radius);" +
                "      padding: 1rem;" +
                "      margin-top: 1.5rem;" +
                "      border: 1px solid #e0e0e0;" +
                "    }" +
                "    .queue-title {" +
                "      font-weight: 600;" +
                "      color: var(--dark-color);" +
                "      margin-bottom: 0.75rem;" +
                "      font-size: 1rem;" +
                "    }" +
                "    .queue-item {" +
                "      display: flex;" +
                "      flex-direction: column;" +
                "      align-items: stretch;" +
                "      gap: 0.75rem;" +
                "      padding: 0.75rem 0;" +
                "      border-bottom: 1px solid #e0e0e0;" +
                "    }" +
                "    .queue-item:last-child {" +
                "      border-bottom: none;" +
                "    }" +
                "    .queue-task-name {" +
                "      width: 100%;" +
                "      font-weight: 600;" +
                "      line-height: 1.4;" +
                "      overflow-wrap: anywhere;" +
                "    }" +
                "    .btn-cancel-queue {" +
                "      width: 100%;" +
                "      padding: 0.55rem 0.75rem;" +
                "      font-size: 0.9rem;" +
                "      background-color: var(--danger-color);" +
                "      color: white;" +
                "      border: none;" +
                "      border-radius: 4px;" +
                "      cursor: pointer;" +
                "    }" +
                "    .btn-cancel-queue:hover {" +
                "      background-color: var(--danger-hover);" +
                "    }" +
                "  </style>" +
                "</head>" +
                "<body>" +
                "  <div class='container'>" +
                "    <h1>" + webTitle + "</h1>" +
                "    <div class='status-box'>" +
                "      <div class='status-title'>" + statusTitle + "</div>" +
                "      " + statusDisplay +
                "    </div>" +
                "    <div class='btn-container'>" +
                "      <form id='stationForm' onsubmit='handleFormSubmit(event, \"stationForm\")'>" +
                "        <div class='form-row'>" +
                "          <select name='station' id='stationSelect' class='form-control'>" +
                "            <option value=''>" + selectStationHint + "</option>" +
                "            " + stationOptions +
                "          </select>" +
                "          <button type='submit' class='btn-success'>" + btnConfirm + "</button>" +
                "        </div>" +
                "      </form>" +
                "      <form id='jackTaskForm' onsubmit='handleFormSubmit(event, \"jackTaskForm\")'>" +
                "        <div class='form-row'>" +
                "          <select name='jackTaskName' id='stationJackTask' class='form-control'>" +
                "            <option value=''>" + selectTaskHint + "</option>" +
                "            " + jackTaskOptions +
                "          </select>" +
                "          <button type='submit' class='btn-success'>" + btnConfirm + "</button>" +
                "        </div>" +
                "      </form>" +
                "      <div class='btn-row'>" +
                "        <button class='btn-primary' onclick='sendCommand(\"park\")'>" + btnPark + "</button>" +
                "        <button class='btn-primary' onclick='sendCommand(\"charge\")'>" + btnCharge + "</button>" +
                "      </div>" +
                "      <div class='btn-row'>" +
                "        <button class='btn-danger' onclick='sendCommand(\"cancel\")'>" + btnCancel + "</button>" +
                "        <button class='btn-warning' onclick='sendCommand(\"pause\")'>" + btnPause + "</button>" +
                "        <button class='btn-warning' onclick='sendCommand(\"resume\")'>" + btnResume + "</button>" +
                "      </div>" +
                "    </div>" +
                "    <div class='queue-box' id='queueBox' style='display:none;'>" +
                "      <div class='queue-title'>" + getLocalizedString(R.string.queued_task_list) + " (<span id='queueCount'>0</span>)</div>" +
                "      <div id='queueList'></div>" +
                "    </div>" +
                "  </div>" +
                "  <script>" +
                "    function fetchQueueStatus() {" +
                "      fetch('/api/queue?t=' + Date.now())" +
                "        .then(response => response.json())" +
                "        .then(data => {" +
                "          updateQueueDisplay(data);" +
                "          setTimeout(fetchQueueStatus, 3000);" +
                "        })" +
                "        .catch(error => {" +
                "          console.error('Error fetching queue:', error);" +
                "          setTimeout(fetchQueueStatus, 5000);" +
                "        });" +
                "    }" +
                "    function updateQueueDisplay(queue) {" +
                "      const queueBox = document.getElementById('queueBox');" +
                "      const queueList = document.getElementById('queueList');" +
                "      const queueCount = document.getElementById('queueCount');" +
                "      if (!queue || queue.length === 0) {" +
                "        queueBox.style.display = 'none';" +
                "        return;" +
                "      }" +
                "      queueBox.style.display = 'block';" +
                "      queueCount.textContent = queue.length;" +
                "      queueList.innerHTML = '';" +
                "      queue.forEach(task => {" +
                "        const taskItem = document.createElement('div');" +
                "        taskItem.className = 'queue-item';" +
                "        const taskName = document.createElement('div');" +
                "        taskName.className = 'queue-task-name';" +
                "        taskName.textContent = task.taskName;" +
                "        const cancelButton = document.createElement('button');" +
                "        cancelButton.className = 'btn-cancel-queue';" +
                "        cancelButton.textContent = '" + getLocalizedString(R.string.cancel_queued_task) + "';" +
                "        cancelButton.onclick = () => cancelQueue(task.queueId);" +
                "        taskItem.appendChild(taskName);" +
                "        taskItem.appendChild(cancelButton);" +
                "        queueList.appendChild(taskItem);" +
                "      });" +
                "    }" +
                "    function cancelQueue(queueId) {" +
                "      fetch('/?cancelQueue=' + queueId, { method: 'GET' })" +
                "        .then(response => {" +
                "          if (response.ok) {" +
                "            console.log('Queue cancelled: ' + queueId);" +
                "            fetchQueueStatus();" +
                "          }" +
                "        })" +
                "        .catch(error => console.error('Error cancelling queue:', error));" +
                "    }" +
                "    document.addEventListener('DOMContentLoaded', function() {" +
                "      fetchQueueStatus();" +
                "    });" +
                "  </script>" +
                "</body>" +
                "</html>";
    }

    // 辅助方法：构建状态卡片
    private String buildStatusCard(String icon, String label, String value, String color, String type) {
        // 为急停状态图标添加唯一的 id
        String safetyIconId = type.equals("emergency") ? " id='safety_icon'" : "";

        return "<div class='status-card' data-type='" + type + "'>" +
                "  <div class='status-icon-container'>" +
                "    <div class='status-icon' style='background:" + color + "'" + safetyIconId + ">" +
                icon +
                "</div>" +
                "  </div>" +
                "  <div class='status-content'>" +
                "    <div class='status-label'>" + label + "</div>" +
                "    <div class='status-value'>" + value + "</div>" +
                "  </div>" +
                "</div>";
    }

    // 辅助方法：获取状态颜色
    private String getStateColor(String state) {
        String idle = LocaleHelper.onServiceGetString(context, R.string.state_idle);
        String running = LocaleHelper.onServiceGetString(context, R.string.state_running);
        String failed = LocaleHelper.onServiceGetString(context, R.string.state_failed);
        String completed = LocaleHelper.onServiceGetString(context, R.string.state_completed);
        if (idle.equals(state)) return "#4CAF50";
        if (running.equals(state)) return "#2196F3";
        if (failed.equals(state)) return "#F44336";
        if (completed.equals(state)) return "#4CAF50";
        return "#673AB7";
    }

    // 辅助方法：获取电池颜色
    private String getBatteryColor(int level) {
        return level >= 80 ? "#4CAF50" :
                level >= 50 ? "#8BC34A" :
                        level >= 20 ? "#FFC107" : "#F44336";
    }

    // 辅助方法：获取置信度颜色
    private String getConfidenceColor(int confidence) {
        return confidence >= 70 ? "#4CAF50" :
                confidence >= 40 ? "#FFC107" : "#F44336";
    }

    private String getStateString(int goalFinish) {
        switch (goalFinish) {
            case 0: return LocaleHelper.onServiceGetString(context, R.string.state_running);
            case 1: return LocaleHelper.onServiceGetString(context, R.string.state_completed);
            case -1: return LocaleHelper.onServiceGetString(context, R.string.state_failed);
            case -2: return LocaleHelper.onServiceGetString(context, R.string.state_idle);
            default: return LocaleHelper.onServiceGetString(context, R.string.unknown);
        }
    }

    private String getLocalizedString(@StringRes int resId) {
        return LocaleHelper.onServiceGetString(context, resId);
    }
}
