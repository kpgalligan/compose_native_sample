# Compose Native Sample

Creating a compose native sample that is easy to share to demonstrate issues with the compiler and the compose plugin (and eventually the runtime)

Starting from JW's external sample shared at:

https://youtrack.jetbrains.com/issue/KT-44381

Updating to latest compose code and JS PS's

# js_prs

Integrating PR's contributed by Andrei Shikov

See "relation chain" and "submitted together" in gerrit for context:

https://android-review.googlesource.com/c/platform/frameworks/support/+/1535139/31

We have naively followed the JS specific changes to see if we can accomplish similar fixes for native, but running into issues. 

If you run this sample with `./gradlew build`, you'll run into

`java.lang.IllegalStateException: org.jetbrains.kotlin.ir.descriptors.WrappedVariableDescriptor@20e5bc24 is not bound`

This is the same issue we were running into with the full example code, so as expected.

If helpful, run with gradle debugging `./gradlew -Dorg.gradle.debug=true build`

Attach Intellij to port 5005 and add a Java Exception breakpoint for `IllegalStateException`. Depending on environment, you may get some noise with `IllegalStateException` that you'll need to ignore. In my case, I got things about "Cipher" init, but they're all caught issues. Telling the breakpoint to ignore caught exceptions doesn't work, so you'll miss the one you want.