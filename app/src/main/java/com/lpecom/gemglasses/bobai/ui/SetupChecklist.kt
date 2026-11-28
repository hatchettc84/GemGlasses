package com.lpecom.gemglasses.bobai.ui

/** One line of the Home setup card. [action] is the button label, or null once the step is done. */
data class SetupStep(val id: String, val label: String, val done: Boolean, val action: String?)

/** The setup steps in the order a technician does them. */
fun setupSteps(
    glassesConnected: Boolean,
    micGranted: Boolean,
    phonePaired: Boolean,
    officeSynced: Boolean,
    heyBobOn: Boolean,
): List<SetupStep> = listOf(
    SetupStep("glasses", "Connect your glasses", glassesConnected, "Register with Meta AI".takeUnless { glassesConnected }),
    SetupStep("mic", "Allow the microphone", micGranted, "Allow".takeUnless { micGranted }),
    SetupStep("pair", "Pair with the office", phonePaired, "Enter code".takeUnless { phonePaired }),
    SetupStep("sync", "Download manuals and jobs", officeSynced, "Sync now".takeUnless { officeSynced }),
    SetupStep("heybob", "Turn on \"Hey Bob\"", heyBobOn, "Turn on".takeUnless { heyBobOn }),
)
