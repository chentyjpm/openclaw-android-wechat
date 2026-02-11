#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <jni.h>

#include <mutex>
#include <string>
#include <vector>

#include <opencv2/core/core.hpp>

#include "net.h"
#include "benchmark.h"
#include "ppocrv5.h"
#include "ppocrv5_dict.h"

extern "C" {

static PPOCRv5* g_ppocr = nullptr;
static std::mutex g_lock;

static jclass objCls = nullptr;
static jmethodID constructorId = nullptr;
static jfieldID x0Id = nullptr;
static jfieldID y0Id = nullptr;
static jfieldID x1Id = nullptr;
static jfieldID y1Id = nullptr;
static jfieldID x2Id = nullptr;
static jfieldID y2Id = nullptr;
static jfieldID x3Id = nullptr;
static jfieldID y3Id = nullptr;
static jfieldID labelId = nullptr;
static jfieldID probId = nullptr;

static std::string decode_text(const Object& obj)
{
    std::string text;
    text.reserve(obj.text.size() * 3);
    for (size_t i = 0; i < obj.text.size(); i++)
    {
        int id = obj.text[i].id;
        if (id >= 0 && id < character_dict_size)
        {
            text += character_dict[id];
        }
    }
    return text;
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    __android_log_print(ANDROID_LOG_DEBUG, "PPOCRv5Ncnn", "JNI_OnLoad");
    ncnn::create_gpu_instance();
    return JNI_VERSION_1_4;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved)
{
    __android_log_print(ANDROID_LOG_DEBUG, "PPOCRv5Ncnn", "JNI_OnUnload");
    {
        std::lock_guard<std::mutex> guard(g_lock);
        delete g_ppocr;
        g_ppocr = nullptr;
    }
    if (objCls)
    {
        JNIEnv* env = nullptr;
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_4) == JNI_OK && env)
        {
            env->DeleteGlobalRef(objCls);
        }
        objCls = nullptr;
    }
    ncnn::destroy_gpu_instance();
}

JNIEXPORT jboolean JNICALL Java_com_tencent_paddleocrncnn_PaddleOCRNcnn_Init(JNIEnv* env, jobject thiz, jobject assetManager)
{
    std::lock_guard<std::mutex> guard(g_lock);

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr)
    {
        __android_log_print(ANDROID_LOG_WARN, "PPOCRv5Ncnn", "asset manager null");
        return JNI_FALSE;
    }

    delete g_ppocr;
    g_ppocr = new PPOCRv5;

    const bool use_fp16 = true;
    const bool use_gpu = ncnn::get_gpu_count() > 0;
    const int ret = g_ppocr->load(
        mgr,
        "PP_OCRv5_mobile_det.ncnn.param",
        "PP_OCRv5_mobile_det.ncnn.bin",
        "PP_OCRv5_mobile_rec.ncnn.param",
        "PP_OCRv5_mobile_rec.ncnn.bin",
        use_fp16,
        use_gpu
    );
    if (ret != 0)
    {
        __android_log_print(ANDROID_LOG_WARN, "PPOCRv5Ncnn", "load model failed ret=%d", ret);
        delete g_ppocr;
        g_ppocr = nullptr;
        return JNI_FALSE;
    }
    g_ppocr->set_target_size(640);

    if (!objCls)
    {
        jclass localObjCls = env->FindClass("com/tencent/paddleocrncnn/PaddleOCRNcnn$Obj");
        if (!localObjCls)
        {
            __android_log_print(ANDROID_LOG_WARN, "PPOCRv5Ncnn", "FindClass Obj failed");
            return JNI_FALSE;
        }
        objCls = reinterpret_cast<jclass>(env->NewGlobalRef(localObjCls));
        constructorId = env->GetMethodID(objCls, "<init>", "(Lcom/tencent/paddleocrncnn/PaddleOCRNcnn;)V");
        x0Id = env->GetFieldID(objCls, "x0", "F");
        y0Id = env->GetFieldID(objCls, "y0", "F");
        x1Id = env->GetFieldID(objCls, "x1", "F");
        y1Id = env->GetFieldID(objCls, "y1", "F");
        x2Id = env->GetFieldID(objCls, "x2", "F");
        y2Id = env->GetFieldID(objCls, "y2", "F");
        x3Id = env->GetFieldID(objCls, "x3", "F");
        y3Id = env->GetFieldID(objCls, "y3", "F");
        labelId = env->GetFieldID(objCls, "label", "Ljava/lang/String;");
        probId = env->GetFieldID(objCls, "prob", "F");
    }

    return JNI_TRUE;
}

JNIEXPORT jobjectArray JNICALL Java_com_tencent_paddleocrncnn_PaddleOCRNcnn_Detect(JNIEnv* env, jobject thiz, jobject bitmap, jboolean use_gpu)
{
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
        return nullptr;

    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return nullptr;

    ncnn::Mat in = ncnn::Mat::from_android_bitmap(env, bitmap, ncnn::Mat::PIXEL_RGB);
    cv::Mat rgb = cv::Mat::zeros(in.h, in.w, CV_8UC3);
    in.to_pixels(rgb.data, ncnn::Mat::PIXEL_RGB);

    std::vector<Object> objects;
    {
        std::lock_guard<std::mutex> guard(g_lock);
        if (!g_ppocr)
            return nullptr;
        g_ppocr->detect_and_recognize(rgb, objects);
    }

    jobjectArray jObjArray = env->NewObjectArray(objects.size(), objCls, nullptr);
    for (size_t i = 0; i < objects.size(); i++)
    {
        jobject jObj = env->NewObject(objCls, constructorId, thiz);

        cv::Point2f corners[4];
        objects[i].rrect.points(corners);

        env->SetFloatField(jObj, x0Id, corners[0].x);
        env->SetFloatField(jObj, y0Id, corners[0].y);
        env->SetFloatField(jObj, x1Id, corners[1].x);
        env->SetFloatField(jObj, y1Id, corners[1].y);
        env->SetFloatField(jObj, x2Id, corners[2].x);
        env->SetFloatField(jObj, y2Id, corners[2].y);
        env->SetFloatField(jObj, x3Id, corners[3].x);
        env->SetFloatField(jObj, y3Id, corners[3].y);

        std::string text = decode_text(objects[i]);
        env->SetObjectField(jObj, labelId, env->NewStringUTF(text.c_str()));
        env->SetFloatField(jObj, probId, objects[i].prob);

        env->SetObjectArrayElement(jObjArray, i, jObj);
        env->DeleteLocalRef(jObj);
    }

    return jObjArray;
}

}
