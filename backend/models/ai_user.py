from sqlalchemy import Column, Integer, String, DateTime, func
from models import Base


class AiUser(Base):
    __tablename__ = "ai_users"

    id = Column(Integer, primary_key=True, autoincrement=True)
    phone = Column(String(32), unique=True, nullable=False, index=True)
    plan_name = Column(String(32), default="免费测试")
    quota_total = Column(Integer, default=20)
    quota_used = Column(Integer, default=0)
    status = Column(String(16), default="active")
    created_at = Column(DateTime, server_default=func.now())
    updated_at = Column(DateTime, server_default=func.now(), onupdate=func.now())
