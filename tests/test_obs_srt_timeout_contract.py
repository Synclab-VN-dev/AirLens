from pathlib import Path


SOURCE = Path("obs-plugin/src/openstream-source.cpp")


def test_obs_srt_input_uses_connect_timeout_and_interruptible_reads():
    source = SOURCE.read_text(encoding="utf-8")

    assert "constexpr int64_t kSrtConnectTimeoutMs = 2'000;" in source
    assert "kSrtIoTimeoutUs" not in source

    format_start = source.index("AVFormatContext *raw_format_ctx = avformat_alloc_context();")
    options_start = source.index("AVDictionary *options = nullptr;", format_start)
    open_start = source.index("avformat_open_input(", options_start)
    format_setup = source[format_start:options_start]
    options = source[options_start:open_start]

    assert "raw_format_ctx->interrupt_callback.callback = ffmpeg_interrupt_callback;" in format_setup
    assert "raw_format_ctx->interrupt_callback.opaque = ctx;" in format_setup
    assert 'av_dict_set_int(&options, "timeout",' not in options
    assert 'av_dict_set_int(&options, "connect_timeout", kSrtConnectTimeoutMs, 0);' in options
