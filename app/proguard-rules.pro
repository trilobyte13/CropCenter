# CropCenter proguard rules.
#
# Deliberately empty of -keep rules: the app has no production reflection, serialization, or
# string-based member lookup — record accessors invoked via method references (Stream::map,
# Comparator::comparing) are compile-time-linked invokedynamic calls that R8 renames together
# with their callers, so no member needs pinning. Release builds therefore get full shrinking,
# optimisation, and obfuscation. If a release-only failure ever surfaces, suspect a genuinely
# reflective path introduced later — most failures crash loudly (NoSuchMethodError), but the
# metadata pipeline fails closed, so a silent HDR-to-SDR degrade in a release build is the one
# quiet symptom to check first (see the release smoke-test item in REQUIREMENTS' device-only
# verification list).
