from pathlib import Path
import re,json
root=Path(__file__).resolve().parents[2]
source=(root/'external/masterdnsvpn/internal/config/client.go').read_text(encoding='utf-8')
fields=dict((a,(b,c)) for a,b,c in re.findall(r'^\s*(\w+)\s+(\S+)\s+`toml:"([A-Z_]+)"`',source,re.M))
defaults=source.split('func defaultClientConfig() ClientConfig {',1)[1].split('\nfunc ',1)[0]
managed={'PROTOCOL_TYPE','DOMAINS','ENCRYPTION_KEY','DATA_ENCRYPTION_METHOD','LISTEN_IP','LISTEN_PORT','SOCKS5_AUTH','SOCKS5_USER','SOCKS5_PASS','LOCAL_DNS_ENABLED','LOCAL_DNS_IP','LOCAL_DNS_PORT'}
labels={
'LOCAL_DNS_CACHE_MAX_RECORDS':'Макс. записей DNS-кэша','LOCAL_DNS_CACHE_TTL_SECONDS':'TTL DNS-кэша, сек','LOCAL_DNS_PENDING_TIMEOUT_SECONDS':'Таймаут DNS, сек','DNS_RESPONSE_FRAGMENT_TIMEOUT_SECONDS':'Таймаут DNS-фрагментов, сек','LOCAL_DNS_CACHE_PERSIST_TO_FILE':'Записывать DNS-кэш','LOCAL_DNS_CACHE_FLUSH_INTERVAL_SECONDS':'Период записи DNS-кэша, сек','RESOLVER_BALANCING_STRATEGY':'Стратегия балансировки резолверов','PACKET_DUPLICATION_COUNT':'Дублирование пакетов','SETUP_PACKET_DUPLICATION_COUNT':'Дублирование setup-пакетов','STREAM_RESOLVER_FAILOVER_RESEND_THRESHOLD':'Порог resend failover','STREAM_RESOLVER_FAILOVER_COOLDOWN':'Пауза failover, сек','RECHECK_INACTIVE_SERVERS_ENABLED':'Перепроверять неактивные резолверы','AUTO_DISABLE_TIMEOUT_SERVERS':'Отключать резолверы по таймаутам','AUTO_DISABLE_TIMEOUT_WINDOW_SECONDS':'Окно таймаутов, сек','BASE_ENCODE_DATA':'Base-кодирование данных','UPLOAD_COMPRESSION_TYPE':'Сжатие отправки','DOWNLOAD_COMPRESSION_TYPE':'Сжатие загрузки','COMPRESSION_MIN_SIZE':'Минимальный размер для сжатия','MIN_UPLOAD_MTU':'Минимальный MTU отправки','MAX_UPLOAD_MTU':'Максимальный MTU отправки','MIN_DOWNLOAD_MTU':'Минимальный MTU загрузки','MAX_DOWNLOAD_MTU':'Максимальный MTU загрузки','AUTO_REMOVE_LOW_MTU_SERVERS':'Исключать резолверы с низким MTU','MTU_TEST_RETRIES':'Повторы проверки MTU','MTU_TEST_TIMEOUT':'Таймаут проверки MTU, сек','MTU_TEST_PARALLELISM':'Параллельность проверки MTU','RX_TX_WORKERS':'RX/TX-воркеры','TUNNEL_PROCESS_WORKERS':'Воркеры туннеля (0 — авто)','TUNNEL_PACKET_TIMEOUT_SECONDS':'Таймаут пакета, сек','DISPATCHER_IDLE_POLL_INTERVAL_SECONDS':'Опрос dispatcher, сек','RX_CHANNEL_SIZE':'Размер RX-канала','SOCKS_UDP_ASSOCIATE_READ_TIMEOUT_SECONDS':'Таймаут SOCKS UDP, сек','CLIENT_TERMINAL_STREAM_RETENTION_SECONDS':'Хранить завершённые потоки, сек','CLIENT_CANCELLED_SETUP_RETENTION_SECONDS':'Хранить отменённые setup, сек','SESSION_INIT_RETRY_BASE_SECONDS':'Базовая пауза retry, сек','SESSION_INIT_RETRY_STEP_SECONDS':'Шаг retry, сек','SESSION_INIT_RETRY_LINEAR_AFTER':'Линейный retry после','SESSION_INIT_RETRY_MAX_SECONDS':'Макс. пауза retry, сек','SESSION_INIT_BUSY_RETRY_INTERVAL_SECONDS':'Retry занятой сессии, сек','SESSION_INIT_RACING_COUNT':'Параллельные init','PING_AGGRESSIVE_INTERVAL_SECONDS':'Частый ping, сек','PING_LAZY_INTERVAL_SECONDS':'Редкий ping, сек','PING_COOLDOWN_INTERVAL_SECONDS':'Cooldown ping, сек','PING_COLD_INTERVAL_SECONDS':'Cold ping, сек','PING_WARM_THRESHOLD_SECONDS':'Warm-порог, сек','PING_COOL_THRESHOLD_SECONDS':'Cool-порог, сек','PING_COLD_THRESHOLD_SECONDS':'Cold-порог, сек','MAX_PACKETS_PER_BATCH':'Макс. пакетов в batch','ARQ_WINDOW_SIZE':'Размер окна ARQ','ARQ_INITIAL_RTO_SECONDS':'Начальный RTO ARQ, сек','ARQ_MAX_RTO_SECONDS':'Макс. RTO ARQ, сек','ARQ_CONTROL_INITIAL_RTO_SECONDS':'Начальный control RTO, сек','ARQ_CONTROL_MAX_RTO_SECONDS':'Макс. control RTO, сек','ARQ_MAX_CONTROL_RETRIES':'Макс. control retry','ARQ_INACTIVITY_TIMEOUT_SECONDS':'Таймаут неактивности ARQ, сек','ARQ_DATA_PACKET_TTL_SECONDS':'TTL data-пакета, сек','ARQ_CONTROL_PACKET_TTL_SECONDS':'TTL control-пакета, сек','ARQ_MAX_DATA_RETRIES':'Макс. повторов данных ARQ','ARQ_DATA_NACK_MAX_GAP':'Макс. разрыв data NACK','ARQ_DATA_NACK_INITIAL_DELAY_SECONDS':'Задержка data NACK, сек','ARQ_DATA_NACK_REPEAT_SECONDS':'Повтор data NACK, сек','ARQ_TERMINAL_DRAIN_TIMEOUT_SECONDS':'Таймаут terminal drain, сек','ARQ_TERMINAL_ACK_WAIT_TIMEOUT_SECONDS':'Ожидание terminal ACK, сек','SAVE_MTU_SERVERS_TO_FILE':'Сохранять результаты MTU в файл','MTU_SERVERS_FILE_NAME':'Имя файла результатов MTU','MTU_SERVERS_FILE_FORMAT':'Формат строки результатов MTU','MTU_USING_SECTION_SEPARATOR_TEXT':'Разделитель результатов MTU','MTU_REMOVED_SERVER_LOG_FORMAT':'Формат исключения резолвера','MTU_ADDED_SERVER_LOG_FORMAT':'Формат возврата резолвера','MTU_REACTIVE_ADDED_SERVER_LOG_FORMAT':'Формат повторной проверки резолвера','LOG_LEVEL':'Уровень журналирования'}
rows=[]
for name,value in re.findall(r'^\s*(\w+):\s*(.+?),\s*$',defaults,re.M):
 if name not in fields: continue
 typ,key=fields[name]
 if key in managed: continue
 value={'compression.TypeOff':'0','compression.DefaultMinSize':'120'}.get(value,value)
 if typ=='string': value=json.loads(value)
 rows.append((key,typ,value))
assert all(k in labels for k,t,v in rows), [k for k,t,v in rows if k not in labels]
q=lambda x:json.dumps(str(x),ensure_ascii=False)
text='''package com.wireturn.app.data.kernel

// Generated from MasterDnsVPN 7de2476 internal/config/client.go defaults.
data class MasterDnsField(val key: String, val type: String, val defaultValue: String, val title: String)
object MasterDnsFields {
    val all = listOf(
'''+',\n'.join(f'        MasterDnsField({q(k)}, {q(t)}, {q(v)}, {q(labels[k])})' for k,t,v in rows)+'''
    )
    val byKey = all.associateBy { it.key }
}
'''
(root/'app/src/main/java/com/wireturn/app/data/kernel/MasterDnsFields.kt').write_text(text,encoding='utf-8')
print('Generated settings:',len(rows))
