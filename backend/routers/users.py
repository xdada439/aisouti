from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session
from sqlalchemy import func
from models import get_db
from models.ai_user import AiUser

router = APIRouter()

QUOTA_PLANS = {
    "free": ("免费测试", 20),
    "100": ("100次权限", 100),
    "500": ("500次权限", 500),
}


class UserRegisterPayload(BaseModel):
    phone: str


class QuotaPayload(BaseModel):
    plan: str = "free"


def _serialize_user(user: AiUser) -> dict:
    remaining = max((user.quota_total or 0) - (user.quota_used or 0), 0)
    return {
        "id": user.id,
        "phone": user.phone,
        "planName": user.plan_name,
        "quotaTotal": user.quota_total,
        "quotaUsed": user.quota_used,
        "quotaRemaining": remaining,
        "status": user.status,
        "createdAt": user.created_at.isoformat(sep=" ") if user.created_at else "",
        "updatedAt": user.updated_at.isoformat(sep=" ") if user.updated_at else "",
    }


@router.get("")
def list_users(db: Session = Depends(get_db)):
    users = db.query(AiUser).order_by(AiUser.id.desc()).all()
    return {"success": True, "items": [_serialize_user(u) for u in users]}


@router.post("/register")
def register_user(body: UserRegisterPayload, db: Session = Depends(get_db)):
    phone = body.phone.strip()
    if not phone:
        raise HTTPException(status_code=400, detail="手机号不能为空")
    user = db.query(AiUser).filter(AiUser.phone == phone).first()
    if not user:
        user = AiUser(phone=phone, plan_name="免费测试", quota_total=20, quota_used=0, status="active")
        db.add(user)
        db.commit()
        db.refresh(user)
    return {"success": True, "user": _serialize_user(user)}


@router.put("/{user_id}/quota")
def update_user_quota(user_id: int, body: QuotaPayload, db: Session = Depends(get_db)):
    user = db.query(AiUser).filter(AiUser.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="用户不存在")
    if body.plan not in QUOTA_PLANS:
        raise HTTPException(status_code=400, detail="权限套餐不存在")
    plan_name, quota = QUOTA_PLANS[body.plan]
    user.plan_name = plan_name
    user.quota_total = quota
    user.quota_used = 0
    user.status = "active"
    db.commit()
    db.refresh(user)
    return {"success": True, "message": "权限已开通", "user": _serialize_user(user)}


@router.post("/{user_id}/consume")
def consume_user_quota(user_id: int, db: Session = Depends(get_db)):
    user = db.query(AiUser).filter(AiUser.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="用户不存在")
    if user.status != "active":
        raise HTTPException(status_code=403, detail="账号已停用")
    if (user.quota_used or 0) >= (user.quota_total or 0):
        raise HTTPException(status_code=403, detail="可用次数不足")
    user.quota_used = (user.quota_used or 0) + 1
    db.commit()
    db.refresh(user)
    return {"success": True, "user": _serialize_user(user)}


@router.get("/stats")
def user_stats(db: Session = Depends(get_db)):
    total = db.query(func.count(AiUser.id)).scalar() or 0
    active = db.query(func.count(AiUser.id)).filter(AiUser.status == "active").scalar() or 0
    return {"success": True, "total": total, "active": active}
