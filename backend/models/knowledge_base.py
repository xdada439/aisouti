from sqlalchemy import Column, Integer, String, Text, Boolean, DateTime, ForeignKey, func
from models import Base


class KnowledgeBase(Base):
    __tablename__ = "knowledge_base"

    id = Column(Integer, primary_key=True, autoincrement=True)
    name = Column(String(256), nullable=False, comment="知识库名称")
    subject = Column(String(128), comment="科目/分类")
    tags = Column(String(512), comment="标签，逗号分隔")
    enabled = Column(Boolean, default=True, comment="是否启用")
    file_count = Column(Integer, default=0, comment="包含文件数")
    chunk_count = Column(Integer, default=0, comment="文本块数")
    created_at = Column(DateTime, server_default=func.now())
    updated_at = Column(DateTime, server_default=func.now(), onupdate=func.now())


class KnowledgeChunk(Base):
    __tablename__ = "knowledge_chunk"

    id = Column(Integer, primary_key=True, autoincrement=True)
    kb_id = Column(Integer, ForeignKey("knowledge_base.id", ondelete="CASCADE"), nullable=False)
    content = Column(Text, nullable=False, comment="文本内容")
    chunk_index = Column(Integer, default=0, comment="块序号")
    source_file = Column(String(500), comment="来源文件名")
    embedding_id = Column(String(256), comment="ChromaDB中的向量ID")
    created_at = Column(DateTime, server_default=func.now())
