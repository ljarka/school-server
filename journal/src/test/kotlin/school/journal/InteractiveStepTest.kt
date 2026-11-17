package school.journal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InteractiveStepTest {
    @Test
    fun `captcha widget in html is an interactive step`() {
        assertTrue(InteractiveStep.isRequired("""<html><div class="g-recaptcha" data-sitekey="k"></div></html>"""))
        assertTrue(InteractiveStep.isRequired("""<html><div class="h-captcha"></div></html>"""))
    }

    @Test
    fun `one-time code input in html is an interactive step`() {
        assertTrue(InteractiveStep.isRequired("""<form><input name="verification_code"></form>"""))
        assertTrue(InteractiveStep.isRequired("""<form><input autocomplete="one-time-code"></form>"""))
    }

    @Test
    fun `json asking for a verification code is an interactive step`() {
        assertTrue(InteractiveStep.isRequired("""{"verification_code": null}"""))
        assertTrue(InteractiveStep.isRequired("""{"twoFactorRequired": true}"""))
    }

    @Test
    fun `ordinary pages mentioning 2fa or two-factor are not interactive steps`() {
        assertFalse(InteractiveStep.isRequired("""<html><a href="/2fa">Two-factor settings</a></html>"""))
        assertFalse(InteractiveStep.isRequired("""{"status":"ok","url":"/OAuth/2fa"}"""))
        assertFalse(InteractiveStep.isRequired("ok"))
    }
}
