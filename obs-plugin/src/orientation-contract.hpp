#pragma once

inline long openstream_sanitize_async_rotation(int rotation) {
  switch (rotation) {
    case 0:
    case 90:
    case 180:
    case 270:
      return static_cast<long>(rotation);
    default:
      return 0L;
  }
}
