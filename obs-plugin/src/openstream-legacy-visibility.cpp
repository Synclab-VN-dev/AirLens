#include <obs-module.h>

// OBS exposes this symbol so a registered source type can be hidden from the
// Add Source UI without unregistering it.  The V7 type must stay registered so
// existing scene collections can still restore legacy OpenStream sources.
extern "C" void obs_enable_source_type(const char *name, bool enable);

extern "C" MODULE_EXPORT void obs_module_post_load(void) {
  obs_enable_source_type("openstream_phone_v7_source", false);
}
