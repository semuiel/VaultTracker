-- Выполняйте в базе vault_tracker. В коде бота используйте параметризованный запрос.
SELECT v.owner_uuid, MAX(v.player_name) AS player_name, i.material,
       SUM(i.amount) AS amount, MIN(v.checked_at_ms) AS oldest_check_ms
FROM vt2_items AS i
JOIN vt2_vaults AS v
  ON v.server_id = i.server_id AND v.vault_id = i.vault_id
WHERE v.active = TRUE AND v.server_id = 'test' AND i.material = 'DIAMOND'
GROUP BY v.owner_uuid, i.material
ORDER BY amount DESC;
