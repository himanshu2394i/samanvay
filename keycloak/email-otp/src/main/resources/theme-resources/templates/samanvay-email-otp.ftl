<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${msg("samanvayEmailOtpTitle")}
    <#elseif section = "form">
        <p id="samanvay-email-otp-sent">${msg("samanvayEmailOtpSent", maskedEmail!"")}</p>
        <form id="kc-email-otp-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <label for="emailCode" class="${properties.kcLabelClass!}">${msg("samanvayEmailOtpLabel")}</label>
                <input id="emailCode" name="emailCode" type="text" inputmode="numeric" autocomplete="one-time-code"
                       autofocus class="${properties.kcInputClass!}" />
            </div>
            <div class="${properties.kcFormGroupClass!}">
                <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                       name="login" id="kc-login" type="submit" value="${msg("doLogIn")}"/>
            </div>
        </form>
        <form id="kc-email-otp-resend" action="${url.loginAction}" method="post">
            <input type="hidden" name="resend" value="true"/>
            <button type="submit" class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}">${msg("samanvayEmailOtpResend")}</button>
        </form>
    </#if>
</@layout.registrationLayout>
