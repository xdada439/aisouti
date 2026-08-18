from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker, declarative_base
from config import DATABASE_URL

engine = create_engine(DATABASE_URL, connect_args={"check_same_thread": False})
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()


def init_db():
    from models.question_bank import QuestionBank  # noqa
    from models.knowledge_base import KnowledgeBase, KnowledgeChunk  # noqa
    from models.ai_user import AiUser  # noqa
    Base.metadata.create_all(bind=engine)
    _ensure_ai_user_columns()


def _ensure_ai_user_columns():
    with engine.begin() as conn:
        rows = conn.execute(text("PRAGMA table_info(ai_users)")).fetchall()
        if not rows:
            return
        existing = {row[1] for row in rows}
        migrations = {
            "plan_name": "ALTER TABLE ai_users ADD COLUMN plan_name VARCHAR(32) DEFAULT '免费测试'",
            "quota_total": "ALTER TABLE ai_users ADD COLUMN quota_total INTEGER DEFAULT 20",
            "quota_used": "ALTER TABLE ai_users ADD COLUMN quota_used INTEGER DEFAULT 0",
            "status": "ALTER TABLE ai_users ADD COLUMN status VARCHAR(16) DEFAULT 'active'",
            "created_at": "ALTER TABLE ai_users ADD COLUMN created_at DATETIME DEFAULT CURRENT_TIMESTAMP",
            "updated_at": "ALTER TABLE ai_users ADD COLUMN updated_at DATETIME DEFAULT CURRENT_TIMESTAMP",
        }
        for column, sql in migrations.items():
            if column not in existing:
                conn.execute(text(sql))


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
