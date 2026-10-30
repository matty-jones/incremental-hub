from contextlib import closing

from fastapi.testclient import TestClient

from app.db import connect
from app.history import payload_sha256
from app.main import app


def _ready(tmp_path, monkeypatch):
    monkeypatch.setenv("HUB_DB_PATH", str(tmp_path / "saves.db"))
    monkeypatch.setenv("HUB_BACKUP_DIR", str(tmp_path / "backups"))


def test_index_pages_ten_thousand_snapshots_without_payloads(tmp_path, monkeypatch):
    _ready(tmp_path, monkeypatch)
    marker = "INDEX-MUST-NOT-INCLUDE-THIS-PAYLOAD"
    selected = f"{marker}-\u0000-42"
    with closing(connect()) as connection:
        connection.execute("BEGIN IMMEDIATE")
        batch = []
        for index in range(10_000):
            payload = selected if index == 42 else f"{marker}-{index}"
            batch.append(
                (
                    f"s-{index:05d}",
                    "shark-incremental",
                    "legacy",
                    payload_sha256(payload),
                    payload,
                    index,
                    index + 1,
                    "phone",
                    "accepted",
                )
            )
        connection.executemany(
            """
            INSERT INTO save_snapshots (
                snapshot_id, game_id, lineage_id, sha256, payload,
                captured_at, received_at, source, integrity
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            batch,
        )
        connection.commit()

    client = TestClient(app)
    seen = []
    after_at = 0
    after_id = ""
    pages = 0
    while True:
        response = client.get(
            "/saves/shark-incremental/snapshots/index",
            params={
                "after_received_at": after_at,
                "after_snapshot_id": after_id,
                "limit": 500,
            },
        )
        assert response.status_code == 200
        body = response.json()
        pages += 1
        entries = body["entries"]
        assert marker not in response.text
        assert "snapshot_payload" not in response.text
        if not entries:
            assert body["complete"] is True
            break
        for entry in entries:
            assert set(entry) == {
                "snapshot_id",
                "game_id",
                "lineage_id",
                "sha256",
                "captured_at_millis",
                "received_at_millis",
                "source",
                "integrity",
            }
            seen.append(entry["snapshot_id"])
        after_at = entries[-1]["received_at_millis"]
        after_id = entries[-1]["snapshot_id"]
        if body["complete"]:
            break
    assert pages == 21
    assert seen == [f"s-{index:05d}" for index in range(10_000)]

    again = client.get(
        "/saves/shark-incremental/snapshots/index",
        params={"after_received_at": after_at, "after_snapshot_id": after_id, "limit": 500},
    )
    assert again.status_code == 200
    assert again.json()["entries"] == []
    assert again.json()["complete"] is True

    one = client.get("/saves/shark-incremental/snapshots/s-00042")
    assert one.status_code == 200
    assert one.json()["snapshot_payload"] == selected
    assert one.json()["sha256"] == payload_sha256(selected)


def test_index_cursor_breaks_ties_and_returns_only_newer_rows(tmp_path, monkeypatch):
    _ready(tmp_path, monkeypatch)
    with closing(connect()) as connection:
        connection.execute("BEGIN IMMEDIATE")
        rows = [
            ("b", "payload-b", 5),
            ("a", "payload-a", 5),
            ("c", "payload-c", 5),
        ]
        for snapshot_id, payload, received in rows:
            connection.execute(
                """
                INSERT INTO save_snapshots (
                    snapshot_id, game_id, lineage_id, sha256, payload,
                    captured_at, received_at, source, integrity
                ) VALUES (?, 'shark-incremental', 'legacy', ?, ?, 5, ?, 'phone', 'accepted')
                """,
                (snapshot_id, payload_sha256(payload), payload, received),
            )
        connection.commit()

    client = TestClient(app)
    seen = []
    after_at = 0
    after_id = ""
    for _ in range(3):
        page = client.get(
            "/saves/shark-incremental/snapshots/index",
            params={"after_received_at": after_at, "after_snapshot_id": after_id, "limit": 1},
        ).json()
        assert len(page["entries"]) == 1
        assert "snapshot_payload" not in page["entries"][0]
        seen.append(page["entries"][0]["snapshot_id"])
        after_at = page["entries"][0]["received_at_millis"]
        after_id = page["entries"][0]["snapshot_id"]
    assert seen == ["a", "b", "c"]

    with closing(connect()) as connection:
        connection.execute("BEGIN IMMEDIATE")
        for snapshot_id, payload in (("d", "payload-d"), ("e", "payload-e")):
            connection.execute(
                """
                INSERT INTO save_snapshots (
                    snapshot_id, game_id, lineage_id, sha256, payload,
                    captured_at, received_at, source, integrity
                ) VALUES (?, 'shark-incremental', 'legacy', ?, ?, 9, 9, 'phone', 'accepted')
                """,
                (snapshot_id, payload_sha256(payload), payload),
            )
        connection.commit()

    fresh = client.get(
        "/saves/shark-incremental/snapshots/index",
        params={"after_received_at": after_at, "after_snapshot_id": after_id, "limit": 10},
    ).json()
    assert [entry["snapshot_id"] for entry in fresh["entries"]] == ["d", "e"]
    assert fresh["complete"] is True
