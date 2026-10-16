package com.wireturn.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wireturn.app.R

/** Localize the pinned core catalog at the UI boundary, preserving native keys and values. */
@Composable
internal fun localizedCoreText(value: String): String {
    val id = when (value) {
        "Таймаут подключения" -> R.string.core_field_connection_timeout
        "Сертификаты CA (JSON-массив PEM)" -> R.string.core_field_ca_certificates_pem_json_array
        "Дополнительные HTTP-заголовки" -> R.string.core_field_additional_http_headers
        "Окно приёма (например 4 MB)" -> R.string.core_field_receive_window_for_example_4_mb
        "Отключить TCP keep-alive" -> R.string.core_field_disable_tcp_keep_alive
        "Начальный период keep-alive" -> R.string.core_field_initial_keep_alive_period
        "Интервал keep-alive" -> R.string.core_field_keep_alive_interval
        "Фрагментация UDP" -> R.string.core_field_udp_fragmentation
        "ALPN (JSON-массив)" -> R.string.core_field_alpn_json_array
        "Минимальная версия TLS" -> R.string.core_field_minimum_tls_version
        "Максимальная версия TLS" -> R.string.core_field_maximum_tls_version
        "Шифры TLS (JSON-массив)" -> R.string.core_field_tls_cipher_suites_json_array
        "Кривые TLS (JSON-массив)" -> R.string.core_field_tls_curves_json_array
        "SHA256 открытого ключа (JSON-массив)" -> R.string.core_field_public_key_sha_256_json_array
        "Клиентский сертификат (JSON-массив PEM)" -> R.string.core_field_client_certificate_pem_json_array
        "Закрытый ключ клиента (JSON-массив PEM)" -> R.string.core_field_client_private_key_pem_json_array
        "Таймаут TLS" -> R.string.core_field_tls_timeout
        "ECH (нативный JSON)" -> R.string.core_field_ech_native_json
        "Reality: гибридный обмен ключами" -> R.string.core_field_reality_hybrid_key_exchange
        "Фрагментация TLS" -> R.string.core_field_tls_fragmentation
        "Задержка фрагментации" -> R.string.core_field_fragmentation_delay
        "Фрагментация TLS records" -> R.string.core_field_tls_record_fragmentation
        "Включить uTLS" -> R.string.core_field_enable_utls
        "Включить SingMux" -> R.string.core_field_enable_singmux
        "Протокол SingMux" -> R.string.core_field_singmux_protocol
        "Brutal (нужна поддержка сервера)" -> R.string.core_field_brutal_requires_server_support
        "Brutal: отдача, Мбит/с" -> R.string.core_field_brutal_upload_mbps
        "Brutal: загрузка, Мбит/с" -> R.string.core_field_brutal_download_mbps
        "Кодирование пакетов" -> R.string.core_field_packet_encoding
        "Транспорт (нативный JSON)" -> R.string.core_field_transport_native_json
        "XHTTP: канал загрузки (нативный JSON)" -> R.string.core_field_xhttp_download_channel_native_json
        "XHTTP xmux (нативный JSON)" -> R.string.core_field_xhttp_xmux_native_json
        "Тип обфускации" -> R.string.core_field_obfuscation_type
        "Пароль обфускации" -> R.string.core_field_obfuscation_password
        "Gecko: минимальный пакет" -> R.string.core_field_gecko_minimum_packet_size
        "Gecko: максимальный пакет" -> R.string.core_field_gecko_maximum_packet_size
        "Профиль BBR" -> R.string.core_field_bbr_profile
        "Отдача Brutal, Мбит/с" -> R.string.core_field_brutal_upload_mbps_alternate
        "Загрузка Brutal, Мбит/с" -> R.string.core_field_brutal_download_mbps_alternate
        "QUIC: таймаут простоя" -> R.string.core_field_quic_idle_timeout
        "Отключить Path MTU discovery" -> R.string.core_field_disable_path_mtu_discovery
        "URL сервера Realm" -> R.string.core_field_realm_server_url
        "Токен Realm" -> R.string.core_field_realm_token
        "STUN-серверы (JSON-массив)" -> R.string.core_field_stun_servers_json_array
        "Проброс порта Realm" -> R.string.core_field_realm_port_mapping
        "Таймаут проброса" -> R.string.core_field_port_mapping_timeout
        "Время жизни проброса" -> R.string.core_field_port_mapping_lifetime
        "Начальный keep-alive, секунд" -> R.string.core_field_initial_keep_alive_seconds
        "Интервал keep-alive, секунд" -> R.string.core_field_keep_alive_interval_seconds
        "TLS (нативный JSON Xray)" -> R.string.core_field_tls_native_xray_json
        "Reality (нативный JSON Xray)" -> R.string.core_field_reality_native_xray_json
        "SHA256 сертификатов через запятую" -> R.string.core_field_certificate_sha_256_hashes_comma_separated
        "Шифры TLS через двоеточие" -> R.string.core_field_tls_cipher_suites_colon_separated
        "Сертификаты (JSON Xray)" -> R.string.core_field_certificates_xray_json
        "Возобновление TLS-сессии" -> R.string.core_field_tls_session_resumption
        "Таймаут TCP, мс" -> R.string.core_field_tcp_timeout_ms
        "XHTTP (нативный JSON)" -> R.string.core_field_xhttp_native_json
        "XHTTP: канал загрузки" -> R.string.core_field_xhttp_download_channel
        "Включить Xray Mux" -> R.string.core_field_enable_xray_mux
        "Число потоков Xray Mux" -> R.string.core_field_xray_mux_stream_count
        "Число потоков XUDP" -> R.string.core_field_xudp_stream_count
        "UDP idle timeout, секунд (2–600)" -> R.string.core_field_udp_idle_timeout_seconds_2_600
        "FinalMask (нативный JSON Xray)" -> R.string.core_field_finalmask_native_xray_json
        "Контроль перегрузки" -> R.string.core_field_congestion_control
        "Brutal: отдача (например 20mbps)" -> R.string.core_field_brutal_upload_for_example_20mbps
        "Brutal: загрузка (например 100mbps)" -> R.string.core_field_brutal_download_for_example_100mbps
        "Максимум соединений" -> R.string.core_field_maximum_connections
        "Минимум потоков" -> R.string.core_field_minimum_streams
        "Максимум потоков" -> R.string.core_field_maximum_streams
        "Окно потока, байт" -> R.string.core_field_stream_window_bytes
        "Окно соединения, байт" -> R.string.core_field_connection_window_bytes
        "Начальный размер пакета" -> R.string.core_field_initial_packet_size
        "Подключение" -> R.string.core_field_connection
        "Протокол" -> R.string.core_field_protocol
        "Транспорт" -> R.string.core_field_transport
        "Для XHTTP; server/server_port/tls и параметры транспорта extended" -> R.string.core_field_for_xhttp_server_server_port_tls_and_extended_transport_settings
        "X25519MLKEM768 для новых серверов Reality. По умолчанию выключен; старым серверам может требоваться выключенное значение. Обновлённые Chrome, Firefox и Safari поддерживают этот обмен." -> R.string.core_field_reality_hybrid_hint
        else -> null
    }
    return if (id == null) value else stringResource(id)
}
